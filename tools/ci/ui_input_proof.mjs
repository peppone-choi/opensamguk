// Relate one input case through TypeScript AST without executing its test module.
import ts from 'typescript';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { posix as posixPath } from 'node:path';

const fail = (reason) => { throw new Error(`UI_PROOF_INVALID: ${reason}`); };
const hash = (source) => createHash('sha256').update(source).digest('hex');
const unbox = (node) => {
  while (node && (ts.isParenthesizedExpression(node) || ts.isAsExpression(node) ||
      ts.isSatisfiesExpression(node) || ts.isNonNullExpression(node))) node = node.expression;
  return node;
};
const name = (node) => node && ts.isIdentifier(node) ? node.text : undefined;
const call = (node, method) => node && ts.isCallExpression(unbox(node)) &&
  ts.isPropertyAccessExpression(unbox(node).expression) && unbox(node).expression.name.text === method;
const receiver = (node) => unbox(node).expression.expression;
const args = (node) => unbox(node).arguments;
// The selected module must resolve request-path parsing to the runtime global.
const requestBuiltins = ['URL'];
const parse = (source, file) => {
  const tree = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, file?.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  if (tree.parseDiagnostics.length) fail('TypeScript 구문 오류');
  return tree;
};

function literal(node, env) {
  node = unbox(node);
  if (!node) fail('명시적 값이 없음');
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text;
  if (ts.isNumericLiteral(node)) return Number(node.text);
  if (node.kind === ts.SyntaxKind.RegularExpressionLiteral) return Object.freeze({ patternSource: node.text });
  if (node.kind === ts.SyntaxKind.TrueKeyword) return true;
  if (node.kind === ts.SyntaxKind.FalseKeyword) return false;
  if (node.kind === ts.SyntaxKind.NullKeyword) return null;
  if (ts.isIdentifier(node) && env.has(node.text)) return env.get(node.text);
  if (ts.isPropertyAccessExpression(node)) {
    const value = literal(node.expression, env);
    // Read explicit own fields of immutable cases; exclude computed lookup and getters.
    if (!value || Array.isArray(value) || typeof value !== 'object' ||
        !Object.hasOwn(value, node.name.text)) fail('고정 객체의 명시적 필드가 아님');
    return value[node.name.text];
  }
  if (ts.isArrayLiteralExpression(node)) return node.elements.map((item) => literal(item, env));
  if (ts.isObjectLiteralExpression(node)) {
    const result = Object.create(null);
    for (const item of node.properties) {
      if (!ts.isPropertyAssignment(item) || ts.isComputedPropertyName(item.name)) fail('동적 객체');
      const key = item.name.text;
      if (Object.hasOwn(result, key)) fail('중복 객체 키');
      result[key] = literal(item.initializer, env);
    }
    return result;
  }
  if (ts.isTemplateExpression(node)) {
    let result = node.head.text;
    for (const span of node.templateSpans) {
      const value = literal(span.expression, env);
      if (!['string', 'number', 'boolean'].includes(typeof value)) fail('동적 제목/경로');
      result += String(value) + span.literal.text;
    }
    return result;
  }
  fail('명시적으로 바인딩되지 않은 값');
}

function optionalLiteral(node, env) {
  try { return literal(node, env); } catch { return undefined; }
}

function bindingIdentifiers(binding) {
  if (!binding) fail('명시적 선언 바인딩 없음');
  if (ts.isIdentifier(binding)) return [binding.text];
  if (ts.isObjectBindingPattern(binding) || ts.isArrayBindingPattern(binding)) {
    return binding.elements.flatMap((item) => {
      if (!ts.isBindingElement(item) || item.initializer || item.dotDotDotToken ||
          (item.propertyName && ts.isComputedPropertyName(item.propertyName))) fail('미검증 선언 바인딩');
      return bindingIdentifiers(item.name);
    });
  }
  fail('미검증 선언 바인딩');
}

function protectCallbackBindings(callback, protectedNames) {
  const seen = new Set();
  for (const parameter of callback.parameters) {
    if (parameter.initializer || parameter.dotDotDotToken) fail('미검증 callback 기본값/나머지 인자');
    for (const identifier of bindingIdentifiers(parameter.name)) {
      if (protectedNames.has(identifier) || seen.has(identifier)) fail('callback binding shadowing');
      seen.add(identifier);
    }
  }
  if (callback.name) {
    if (protectedNames.has(callback.name.text) || seen.has(callback.name.text)) fail('callback binding shadowing');
    seen.add(callback.name.text);
  }
  return seen;
}

function declarations(statement, env) {
  if (!ts.isVariableStatement(statement)) return;
  if (!(statement.declarationList.flags & ts.NodeFlags.Const)) return;
  for (const declaration of statement.declarationList.declarations) {
    if (!ts.isIdentifier(declaration.name)) continue;
    const value = optionalLiteral(declaration.initializer, env);
    if (value !== undefined) env.set(declaration.name.text, value);
  }
}

function importBindings(tree, payload) {
  const bindings = { test: undefined, expect: undefined, press: undefined, env: new Map() };
  for (const statement of tree.statements) {
    if (!ts.isImportDeclaration(statement) || !ts.isStringLiteral(statement.moduleSpecifier)) continue;
    const module = statement.moduleSpecifier.text;
    const canonicalParity = module.startsWith('.') &&
      posixPath.normalize(posixPath.join(posixPath.dirname(payload.path), module)) === 'web/game/e2e/support/parity';
    if (module !== '@playwright/test' && !canonicalParity)
      fail('미검증 시험 import');
    if (requestBuiltins.includes(statement.importClause?.name?.text)) fail('global binding shadowing');
    const imports = statement.importClause?.namedBindings;
    if (imports && ts.isNamespaceImport(imports) && requestBuiltins.includes(imports.name.text))
      fail('global binding shadowing');
    if (!imports || !ts.isNamedImports(imports)) continue;
    for (const item of imports.elements) {
      if (requestBuiltins.includes(item.name.text)) fail('global binding shadowing');
      const exported = item.propertyName?.text ?? item.name.text;
      if (module === '@playwright/test' && ['test', 'expect'].includes(exported)) {
        bindings[exported] = item.name.text;
      }
      if (canonicalParity && ['press', 'BOTH'].includes(exported)) {
        // Python supplies the canonical repository helper, never arbitrary imports.
        if (!payload.paritySource) fail('parity helper 원천 없음');
        const helper = parse(payload.paritySource, 'parity.ts');
        if (exported === 'BOTH') {
          const values = new Map();
          helper.statements.forEach((s) => declarations(s, values));
          if (values.get('BOTH') !== '@both') fail('BOTH 계약 불일치');
          bindings.env.set(item.name.text, '@both');
        } else {
          const press = helper.statements.find((s) => ts.isFunctionDeclaration(s) &&
            s.name?.text === 'press' && s.modifiers?.some((m) => m.kind === ts.SyntaxKind.ExportKeyword));
          if (!press || press.parameters[0]?.name.getText(helper) !== 'locator') fail('press 계약 불일치');
          // Accept only the two reviewed functions; changed helpers require another review.
          const mobile = helper.statements.find((s) => ts.isFunctionDeclaration(s) && s.name?.text === 'isMobile');
          if (hash(press.getText(helper)) !== '02c9e2a4fc444608668acf348e2e3e2149ac0db5a4712b3b516a761ff03962eb' ||
              !mobile || hash(mobile.getText(helper)) !== '1f19b8afe902f2a5e02933b1badc9a7d2f9f3ee99f06309db46c485d67e9c9a4')
            fail('검토한 press/isMobile 구현과 다름');
          bindings.press = item.name.text;
        }
      }
    }
  }
  if (!bindings.test || !bindings.expect) fail('Playwright test/expect import 없음');
  return bindings;
}

function testKind(node, bindings) {
  const expression = unbox(node.expression);
  if (name(expression) === bindings.test) return 'test';
  if (ts.isPropertyAccessExpression(expression) && name(expression.expression) === bindings.test) {
    return expression.name.text;
  }
  return undefined;
}

function selectCases(tree, bindings, inputId) {
  const selected = [];
  const seenTitles = new Set();
  const boundObjects = new Set();
  const visitStatements = (statements, outer, disabled = false) => {
    const env = new Map(outer);
    for (const statement of statements) {
      if (ts.isVariableStatement(statement)) {
        for (const declaration of statement.declarationList.declarations) {
          const init = unbox(declaration.initializer);
          if (!init || (optionalLiteral(init, env) === undefined &&
              !ts.isArrowFunction(init) && !ts.isFunctionExpression(init)))
            fail('미검증 등록 초기화');
          if (bindingIdentifiers(declaration.name).some((identifier) =>
              [...requestBuiltins, bindings.test, bindings.expect, bindings.press, ...bindings.env.keys()].filter(Boolean).includes(identifier)))
            fail('import binding shadowing');
        }
      }
      if (ts.isFunctionDeclaration(statement) &&
          [...requestBuiltins, bindings.test, bindings.expect, bindings.press, ...env.keys()].filter(Boolean).includes(name(statement.name)))
        fail('import binding shadowing');
      if (ts.isExpressionStatement(statement)) {
        const expression = unbox(statement.expression);
        if (ts.isBinaryExpression(expression) || ts.isPostfixUnaryExpression(expression)) fail('등록 자료 재할당');
        if (ts.isCallExpression(expression) && ts.isPropertyAccessExpression(expression.expression) && env.has(name(expression.expression.expression)))
          fail('등록 자료 변경 가능 호출');
      }
      declarations(statement, env);
      for (const [key, value] of env) if (value && typeof value === 'object') boundObjects.add(key);
      if (ts.isForOfStatement(statement)) {
        if (!ts.isVariableDeclarationList(statement.initializer) ||
            !(statement.initializer.flags & ts.NodeFlags.Const)) fail('동적 사례 반복');
        const values = literal(statement.expression, env);
        if (!Array.isArray(values)) fail('사례 배열 없음');
        const binding = statement.initializer.declarations[0]?.name;
        if (bindingIdentifiers(binding).some((identifier) =>
            [...requestBuiltins, bindings.test, bindings.expect, bindings.press, ...env.keys()].filter(Boolean).includes(identifier)))
          fail('등록 반복 binding shadowing');
        for (const value of values) {
          const caseEnv = new Map(env);
          if (ts.isIdentifier(binding)) {
            caseEnv.set(binding.text, value);
            if (value && typeof value === 'object') boundObjects.add(binding.text);
          }
          else if (ts.isArrayBindingPattern(binding) && Array.isArray(value)) {
            binding.elements.forEach((item, index) => {
              if (!ts.isBindingElement(item) || !ts.isIdentifier(item.name) || item.initializer || item.dotDotDotToken)
                fail('동적 사례 구조 분해');
              caseEnv.set(item.name.text, value[index]);
            });
          } else fail('지원하지 않는 사례 바인딩');
          visitStatements(ts.isBlock(statement.statement) ? statement.statement.statements : [statement.statement], caseEnv, disabled);
        }
        continue;
      }
      if (!ts.isExpressionStatement(statement) || !ts.isCallExpression(statement.expression)) {
        if (!ts.isImportDeclaration(statement) && !ts.isVariableStatement(statement) &&
            !ts.isFunctionDeclaration(statement) && !ts.isEmptyStatement(statement))
          fail('미검증 등록 제어 흐름');
        continue;
      }
      const registration = statement.expression;
      const kind = testKind(registration, bindings);
      if (!kind) fail('미검증 등록 실행');
      const callback = registration.arguments.at(-1);
      if (kind === 'describe') {
        if (callback && (ts.isArrowFunction(callback) || ts.isFunctionExpression(callback)) && ts.isBlock(callback.body)) {
          protectCallbackBindings(callback, new Set([...requestBuiltins, bindings.test, bindings.expect, bindings.press, ...env.keys()].filter(Boolean)));
          if (callback.parameters.length) fail('미검증 describe callback 인자');
          visitStatements(callback.body.statements, env, disabled);
        }
        continue;
      }
      if (!['test', 'skip', 'fixme', 'only'].includes(kind)) fail('미검증 시험 hook/설정');
      const title = literal(registration.arguments[0], env);
      if (typeof title !== 'string' || !title.startsWith(`[${inputId}] `)) continue;
      if (disabled || kind !== 'test') fail('skip/fixme/only 사례');
      if (seenTitles.has(title)) fail('중복 사례 제목');
      seenTitles.add(title);
      if (!callback || !(ts.isArrowFunction(callback) || ts.isFunctionExpression(callback)) || !ts.isBlock(callback.body))
        fail('독립 시험 callback 없음');
      const options = registration.arguments.length === 3 ? literal(registration.arguments[1], env) : {};
      const tags = typeof options.tag === 'string' ? [options.tag] : options.tag;
      if (!Array.isArray(tags) || !tags.includes('@both')) fail('@both 선택 태그 없음');
      selected.push({ title, callback, env });
    }
  };
  visitStatements(tree.statements, bindings.env);
  // Protect original arguments against mutation through aliases and nested branches.
  for (const item of selected) {
    const local = new Map(item.env);
    const collect = (node) => {
      if (ts.isVariableStatement(node)) {
        declarations(node, local);
        for (const [key, value] of local) if (value && typeof value === 'object') boundObjects.add(key);
      }
      ts.forEachChild(node, collect);
    };
    collect(item.callback.body);
  }
  // Nested callbacks can mutate fixed data; inspect them without running the test.
  const rootName = (node) => {
    node = unbox(node);
    while (node && (ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node))) node = unbox(node.expression);
    return name(node);
  };
  const criticalArgument = (node) => {
    node = unbox(node);
    if (!boundObjects.has(rootName(node))) return false;
    if (ts.isIdentifier(node) || ts.isElementAccessExpression(node)) return true;
    return ts.isPropertyAccessExpression(node) && node.name.text === 'args';
  };
  const guard = (node) => {
    if ((ts.isBinaryExpression(node) && node.operatorToken.kind >= ts.SyntaxKind.FirstAssignment &&
         node.operatorToken.kind <= ts.SyntaxKind.LastAssignment && boundObjects.has(rootName(node.left))) ||
        (ts.isDeleteExpression(node) && boundObjects.has(rootName(node.expression))) ||
        ((ts.isPrefixUnaryExpression(node) || ts.isPostfixUnaryExpression(node)) &&
         [ts.SyntaxKind.PlusPlusToken, ts.SyntaxKind.MinusMinusToken].includes(node.operator) && boundObjects.has(rootName(node.operand))))
      fail('고정 사례 자료 변경');
    if (ts.isCallExpression(node)) {
      const callee = unbox(node.expression);
      if (ts.isPropertyAccessExpression(callee) && boundObjects.has(rootName(callee.expression)))
        fail('고정 사례 객체의 동적 호출');
      const assertion = ts.isPropertyAccessExpression(callee) &&
        ['toEqual', 'toStrictEqual'].includes(callee.name.text) && ts.isCallExpression(unbox(callee.expression)) &&
        name(unbox(callee.expression).expression) === bindings.expect;
      if (name(callee) !== bindings.expect && !assertion && node.arguments.some(criticalArgument))
        fail('고정 사례 자료를 미검증 함수에 전달');
    }
    ts.forEachChild(node, guard);
  };
  guard(tree);
  if (!selected.length) fail('정확한 [inputId] 시험 사례 없음');
  return selected;
}

function compoundInputSelector(selector) {
  // Accept a small CSS compound grammar only. No XPath, selector engines,
  // combinators, pseudo selectors or escaped/embedded selector strings.
  if (typeof selector !== 'string' || !selector) fail('미검증 CSS 영역 선택자');
  let rest = selector;
  const tag = rest.match(/^(?:[A-Za-z][A-Za-z0-9_-]*|\*)/);
  if (tag) rest = rest.slice(tag[0].length);
  let inputId;
  while (rest) {
    const named = rest.match(/^[.#][A-Za-z_][A-Za-z0-9_-]*/);
    if (named) { rest = rest.slice(named[0].length); continue; }
    const attribute = rest.match(/^\[([A-Za-z_][A-Za-z0-9_-]*)(?:=(?:"([^"\\\r\n]*)"|'([^'\\\r\n]*)'|([A-Za-z0-9_-]+)))?\]/);
    if (!attribute) fail('영역을 벗어나거나 해석되지 않는 CSS 선택자');
    if (attribute[1] === 'data-input-id') {
      const value = attribute[2] ?? attribute[3] ?? attribute[4];
      if (!value || inputId !== undefined) fail('입력 영역 ID가 유일한 고정값이 아님');
      inputId = value;
    }
    rest = rest.slice(attribute[0].length);
  }
  return inputId;
}

function locatorKey(node, env, locators, pageName) {
  node = unbox(node);
  if (ts.isIdentifier(node)) return locators.get(node.text);
  if (!ts.isCallExpression(node) || !ts.isPropertyAccessExpression(node.expression)) return undefined;
  const method = node.expression.name.text;
  const parent = name(node.expression.expression) === pageName ? { key: 'page', inputId: undefined } :
    locatorKey(node.expression.expression, env, locators, pageName);
  if (!parent) return undefined;
  if (!['getByRole', 'getByTestId', 'locator', 'getByText', 'first', 'last', 'nth', 'filter'].includes(method)) return undefined;
  const values = node.arguments.map((arg) => literal(arg, env));
  let inputId = parent.inputId;
  if (method === 'locator') {
    if (values.length !== 1) fail('미검증 locator 인자');
    inputId = compoundInputSelector(values[0]) ?? parent.inputId;
  }
  if (['first', 'last'].includes(method) && values.length !== 0) fail('미검증 locator 선택 인자');
  if (method === 'nth' && (values.length !== 1 || !Number.isSafeInteger(values[0]) || values[0] < 0))
    fail('미검증 locator 순번');
  return { key: `${parent.key}/${method}:${JSON.stringify(values)}`, inputId };
}

function requestPredicate(node, env, pageName, paths) {
  if (!call(node, 'waitForRequest') || name(receiver(node)) !== pageName) return false;
  if (args(node).length < 1 || args(node).length > 2) fail('미검증 waiter 인자');
  if (args(node).length === 2) literal(args(node)[1], env);
  const predicate = args(node)[0];
  if (!predicate || !ts.isArrowFunction(predicate) || !ts.isIdentifier(predicate.parameters[0]?.name)) return false;
  protectCallbackBindings(predicate, new Set(requestBuiltins));
  if (predicate.parameters.length !== 1) fail('미검증 request predicate 인자');
  const requestName = predicate.parameters[0].name.text;
  const body = unbox(predicate.body);
  const clauses = [];
  const flatten = (expr) => {
    expr = unbox(expr);
    if (ts.isBinaryExpression(expr) && expr.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken) {
      flatten(expr.left); flatten(expr.right);
    } else clauses.push(expr);
  };
  flatten(body);
  let post = false, path = false;
  for (const clause of clauses) {
    if (!ts.isBinaryExpression(clause) || clause.operatorToken.kind !== ts.SyntaxKind.EqualsEqualsEqualsToken) return false;
    const left = unbox(clause.left);
    const right = optionalLiteral(clause.right, env);
    if (call(left, 'method') && args(left).length === 0 && name(receiver(left)) === requestName && right === 'POST') post = true;
    else if (ts.isPropertyAccessExpression(left) && left.name.text === 'pathname') {
      const url = unbox(left.expression);
      if (ts.isNewExpression(url) && name(url.expression) === 'URL' && url.arguments?.length === 1 &&
          call(url.arguments[0], 'url') && args(url.arguments[0]).length === 0 && name(receiver(url.arguments[0])) === requestName && paths.includes(right)) path = true;
      else return false;
    } else return false;
  }
  return post && path;
}

function proveCase(selected, bindings, contract) {
  const { callback } = selected;
  const pageBinding = callback.parameters[0]?.name;
  if (!pageBinding || !ts.isObjectBindingPattern(pageBinding)) fail('page fixture 바인딩 없음');
  const page = pageBinding.elements.find((item) => (item.propertyName?.text ?? item.name.text) === 'page');
  if (!page || !ts.isIdentifier(page.name)) fail('page fixture 없음');
  const pageName = page.name.text;
  if (callback.parameters.length > 2) fail('미검증 callback 인자 수');
  const callbackNames = protectCallbackBindings(callback,
    new Set([...requestBuiltins, bindings.test, bindings.expect, bindings.press, ...selected.env.keys()].filter(Boolean)));
  const declarationNames = new Set([...requestBuiltins, bindings.test, bindings.expect, bindings.press,
    ...callbackNames, ...selected.env.keys()].filter(Boolean));
  const declare = (identifier) => {
    if (!identifier || declarationNames.has(identifier)) fail('지역 binding shadowing');
    declarationNames.add(identifier);
  };
  const env = new Map(selected.env), locators = new Map(), waiters = new Map(), requests = new Map();
  const submissions = [], anchors = new Set();
  // Only the selected input scope can supply the sending interaction. Runtime
  // observation still has to prove the actual POST; a static locator is no grant.
  const inputScope = (key) => key?.inputId === contract.inputId;
  const rejectRequestCreation = (node) => {
    if (ts.isNewExpression(node) && ['XMLHttpRequest', 'WebSocket'].includes(name(unbox(node.expression))))
      fail('프로그램 요청 생성');
    if (ts.isCallExpression(node)) {
      const target = unbox(node.expression);
      if (['fetch', 'XMLHttpRequest', 'WebSocket', 'sendBeacon'].includes(name(target)) ||
          (ts.isPropertyAccessExpression(target) &&
           ['evaluate', 'evaluateHandle', 'route', 'routeFromHAR', 'addInitScript', 'exposeFunction',
            'post', 'put', 'patch', 'delete', 'fetch', 'sendBeacon', 'dispatchEvent'].includes(target.name.text)))
        fail('프로그램 요청/미검증 browser 실행은 UI 보내기 증거가 아님');
    }
    ts.forEachChild(node, rejectRequestCreation);
  };
  rejectRequestCreation(callback.body);
  let index = 0;
  const processExpression = (expression, awaited) => {
    expression = unbox(expression);
    if (!ts.isCallExpression(expression)) fail('미검증 실행식');
    if (call(expression, 'skip') || call(expression, 'fixme')) fail('조건부 skip/fixme');
    if (awaited && (call(expression, 'click') || call(expression, 'tap'))) {
      const key = locatorKey(receiver(expression), env, locators, pageName);
      if (!key) fail('미검증 화면 보내기 호출');
      anchors.add(key); submissions.push({ order: index, key });
    }
    if (awaited && bindings.press && name(expression.expression) === bindings.press) {
      const key = locatorKey(args(expression)[0], env, locators, pageName);
      if (!key) fail('press 화면 locator 바인딩 없음');
      anchors.add(key); submissions.push({ order: index, key });
    }
    if (awaited && ['fill', 'selectOption', 'check'].some((method) => call(expression, method))) {
      const key = locatorKey(receiver(expression), env, locators, pageName);
      if (key) anchors.add(key);
    }
    const uiAction = ['click', 'tap', 'fill', 'selectOption', 'check'].some((method) => call(expression, method));
    const assertion = ts.isPropertyAccessExpression(expression.expression) &&
      ts.isCallExpression(unbox(receiver(expression))) && name(unbox(receiver(expression)).expression) === bindings.expect;
    if (uiAction) {
      if (!awaited || !locatorKey(receiver(expression), env, locators, pageName)) fail('미검증 locator 조작');
      for (const argument of args(expression)) literal(argument, env);
      return;
    }
    if (bindings.press && name(expression.expression) === bindings.press) {
      if (!awaited) fail('기다리지 않은 press');
      if (args(expression).length !== 2 || !ts.isIdentifier(unbox(args(expression)[1])) ||
          !ts.isIdentifier(callback.parameters[1]?.name) ||
          name(unbox(args(expression)[1])) !== name(callback.parameters[1].name))
        fail('미검증 press 호출 인자');
      return;
    }
    if (call(expression, 'goto') && name(receiver(expression)) === pageName && awaited && !waiters.size) {
      for (const argument of args(expression)) literal(argument, env);
      return;
    }
    if (!assertion) fail('미검증 helper/실행 호출');
    const expectInvocation = unbox(receiver(expression));
    if (args(expectInvocation).length < 1 || args(expectInvocation).length > 2)
      fail('미검증 expect 인자');
    for (const extra of args(expectInvocation).slice(1)) {
      if (typeof literal(extra, env) !== 'string') fail('expect 설명은 고정 문자열이어야 함');
    }
    const assertionValue = unbox(args(unbox(receiver(expression)))[0]);
    if (locatorKey(assertionValue, env, locators, pageName)) {
      for (const argument of args(expression)) literal(argument, env);
      return;
    }
    if (!call(assertionValue, 'postDataJSON') || args(assertionValue).length !== 0 ||
        !['toEqual', 'toStrictEqual'].some((method) => call(expression, method)))
      fail('미검증 assertion 실행 인자');
    const expectCall = unbox(receiver(expression));
    if (!ts.isCallExpression(expectCall) || name(expectCall.expression) !== bindings.expect) return;
    let body = unbox(args(expectCall)[0]);
    if (!call(body, 'postDataJSON')) return;
    let request = unbox(receiver(body));
    let waited;
    if (ts.isAwaitExpression(request) && ts.isIdentifier(unbox(request.expression))) {
      waited = waiters.get(unbox(request.expression).text);
    } else if (ts.isIdentifier(request)) waited = requests.get(request.text);
    if (!waited) fail('단언이 실제 관찰 request와 연결되지 않음');
    const expected = literal(args(expression)[0], env);
    if (args(expression).length !== 1) fail('본문 matcher 인자는 정본 객체 하나여야 함');
    if (!expected || Array.isArray(expected) || typeof expected !== 'object') fail('명시적 본문 객체 없음');
    const actualKeys = Object.keys(expected).sort(), required = Object.keys(contract.body).sort();
    if (JSON.stringify(actualKeys) !== JSON.stringify(required)) fail('정본 본문 키 불일치');
    for (const [key, type] of Object.entries(contract.body)) {
      const value = expected[key];
      if (type === 'positive-int' && !(Number.isSafeInteger(value) && value > 0 && value <= 2147483647)) fail('본문 양의 ID 불일치');
      if (type.startsWith('bounded-positive:') && !(Number.isSafeInteger(value) && value > 0 && value <= Number(type.slice('bounded-positive:'.length)))) fail('정본 본문 상한 불일치');
      if (type.startsWith('enum:') && !type.slice('enum:'.length).split(',').includes(value)) fail('정본 본문 enum 불일치');
      if (type === 'string' && !(typeof value === 'string' && value.trim().length > 0)) fail('본문 문자열 불일치');
      if (type === 'boolean' && typeof value !== 'boolean') fail('본문 boolean 불일치');
      if (type === 'positive-ints' && !(Array.isArray(value) && value.length > 0 && new Set(value).size === value.length && value.every((v) => Number.isSafeInteger(v) && v > 0 && v <= 2147483647))) fail('본문 ID 배열 불일치');
    }
    if (!anchors.size || !submissions.some(({ order, key }) => waited < order && order < index && inputScope(key)))
      fail('waiter→선택 입력의 화면 보내기→본문 단언 순서 없음');
    return expected;
  };
  let proven;
  const forbidden = (node) => {
    let found = false;
    const visit = (item) => {
      if (ts.isReturnStatement(item) || ts.isThrowStatement(item) || call(item, 'skip') || call(item, 'fixme') ||
          call(item, 'waitForRequest') || call(item, 'postDataJSON')) found = true;
      ts.forEachChild(item, visit);
    };
    visit(node);
    return found;
  };
  const processStatements = (statements) => { for (const statement of statements) {
    index++;
    if (ts.isVariableStatement(statement)) {
      if (!(statement.declarationList.flags & ts.NodeFlags.Const)) fail('가변 사례 변수');
      for (const declaration of statement.declarationList.declarations) {
        if (!ts.isIdentifier(declaration.name)) fail('지원하지 않는 지역 구조 분해');
        const identifier = declaration.name.text, init = unbox(declaration.initializer);
        declare(identifier);
        if (!init) fail('초기화 없는 변수');
        const value = optionalLiteral(init, env);
        if (value !== undefined) env.set(identifier, value);
        const key = locatorKey(init, env, locators, pageName);
        if (key) locators.set(identifier, key);
        const isWaiter = requestPredicate(init, env, pageName, contract.paths);
        if (isWaiter) waiters.set(identifier, index);
        const awaitedRequest = ts.isAwaitExpression(init) && ts.isIdentifier(unbox(init.expression)) && waiters.has(unbox(init.expression).text);
        if (value === undefined && !key && !isWaiter && !awaitedRequest &&
            !ts.isArrowFunction(init) && !ts.isFunctionExpression(init)) fail('미검증 변수 초기화 호출');
        if (ts.isAwaitExpression(init) && ts.isIdentifier(unbox(init.expression)) && waiters.has(unbox(init.expression).text)) {
          const waitOrder = waiters.get(unbox(init.expression).text);
          if (!submissions.some(({ order, key }) => waitOrder < order && order < index && inputScope(key)))
            fail('선택 입력 보내기 전에 기다린 request');
          requests.set(identifier, waitOrder);
        }
      }
      continue;
    }
    if (ts.isExpressionStatement(statement)) {
      const awaited = ts.isAwaitExpression(statement.expression);
      const expression = awaited ? statement.expression.expression : statement.expression;
      if (ts.isBinaryExpression(expression) || ts.isPostfixUnaryExpression(expression) || ts.isPrefixUnaryExpression(expression)) fail('가변/재할당 식');
      const result = processExpression(expression, awaited);
      if (result !== undefined) proven = result;
    } else if (ts.isIfStatement(statement)) {
      // Ignore navigation-only branches; reject conditional proof and early returns.
      if (forbidden(statement) || waiters.size) fail('조건부 증명/조기 종료');
      const calls = [];
      const scan = (node) => { if (ts.isCallExpression(node)) calls.push(node); ts.forEachChild(node, scan); };
      scan(statement);
      if (calls.length) fail('미검증 조건부 실행 호출');
    } else if (ts.isForOfStatement(statement)) {
      if (!ts.isVariableDeclarationList(statement.initializer) || !(statement.initializer.flags & ts.NodeFlags.Const)) fail('동적 화면 조작 반복');
      const variable = statement.initializer.declarations[0]?.name;
      const values = literal(statement.expression, env);
      if (!variable || !ts.isIdentifier(variable) || !Array.isArray(values)) fail('동적 화면 조작 바인딩');
      declare(variable.text);
      for (const value of values) {
        env.set(variable.text, value);
        processStatements(ts.isBlock(statement.statement) ? statement.statement.statements : [statement.statement]);
        env.delete(variable.text);
      }
      declarationNames.delete(variable.text);
    } else if (ts.isTryStatement(statement) || ts.isReturnStatement(statement) || ts.isThrowStatement(statement)) {
      fail('지원하지 않는 시험 제어 흐름');
    } else if (ts.isFunctionDeclaration(statement)) {
      declare(statement.name?.text);
      // A declared callback alone does not prove execution; it cannot replace
      // an imported assertion/helper, fixture, inherited row or observed value.
    } else fail('지원하지 않는 시험 문장');
  } };
  processStatements(callback.body.statements);
  if (proven === undefined) fail('같은 사례의 화면 보내기/POST/본문 단언 없음');
  return { title: selected.title, expectedBody: proven, projects: ['desktop', 'mobile'] };
}

function validate(payload) {
  if (payload.mode === 'imports') {
    const tree = parse(payload.source, payload.path);
    return { imports: tree.statements.filter((s) => ts.isImportDeclaration(s) && ts.isStringLiteral(s.moduleSpecifier))
      .map((s) => s.moduleSpecifier.text).filter((s) => s.startsWith('.')) };
  }
  if (typeof payload.source !== 'string' || typeof payload.inputId !== 'string' || !payload.contract?.paths?.length || !payload.contract.body)
    fail('proof protocol 불일치');
  const tree = parse(payload.source, payload.path);
  const bindings = importBindings(tree, payload);
  const selected = selectCases(tree, bindings, payload.inputId);
  return {
    state: 'STATIC_PROOF_VALID', inputId: payload.inputId, path: payload.path,
    sourceSha256: hash(payload.source), paritySha256: payload.paritySource ? hash(payload.paritySource) : null,
    imports: tree.statements.filter((s) => ts.isImportDeclaration(s) && ts.isStringLiteral(s.moduleSpecifier))
      .map((s) => s.moduleSpecifier.text).filter((s) => s.startsWith('.')),
    cases: selected.map((item) => proveCase(item, bindings, { ...payload.contract, inputId: payload.inputId })),
    uiRuntimeExecuted: false,
  };
}

try {
  process.stdout.write(JSON.stringify(validate(JSON.parse(readFileSync(0, 'utf8')))) + '\n');
} catch (error) {
  process.stderr.write((error instanceof Error ? error.message : 'UI_PROOF_INVALID') + '\n');
  process.exitCode = 1;
}

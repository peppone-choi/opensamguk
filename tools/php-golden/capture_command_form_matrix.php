<?php

declare(strict_types=1);

$options = getopt('', ['root::', 'out::']);
$repoRoot = realpath($options['root'] ?? dirname(__DIR__, 2));
if ($repoRoot === false) {
    fwrite(STDERR, "repository root not found\n");
    exit(64);
}

$commandRoot = $repoRoot . '/legacy/devsam-core/hwe/sammo/Command';
$files = array_merge(
    glob($commandRoot . '/General/*.php') ?: [],
    glob($commandRoot . '/Nation/*.php') ?: [],
);
sort($files, SORT_STRING);
if (count($files) !== 93) {
    fwrite(STDERR, 'expected 93 PHP command files, got ' . count($files) . "\n");
    exit(1);
}

$classes = [];
foreach ($files as $file) {
    $source = file_get_contents($file);
    if ($source === false) {
        fwrite(STDERR, "cannot read {$file}\n");
        exit(1);
    }
    $code = pathinfo($file, PATHINFO_FILENAME);
    preg_match('/class\s+\S+\s+extends\s+([^\s{]+)/u', $source, $parentMatch);
    preg_match_all(
        '/(?:\$this->arg\s*\[\s*[\'"]([^\'"]+)[\'"]\s*\]|key_exists\(\s*[\'"]([^\'"]+)[\'"]\s*,\s*\$this->arg\s*\))/u',
        $source,
        $fieldMatches,
        PREG_SET_ORDER,
    );
    $fields = [];
    foreach ($fieldMatches as $match) {
        $field = $match[1] !== '' ? $match[1] : $match[2];
        if (!in_array($field, $fields, true)) {
            $fields[] = $field;
        }
    }
    $classes[$code] = [
        'parent' => isset($parentMatch[1]) ? basename(str_replace('\\', '/', $parentMatch[1])) : null,
        'reqArg' => preg_match('/static\s+public\s+\$reqArg\s*=\s*true/u', $source) === 1,
        'fields' => $fields,
    ];
}

$resolveShape = function (string $code) use (&$resolveShape, $classes): array {
    $shape = $classes[$code] ?? ['parent' => null, 'reqArg' => false, 'fields' => []];
    $parent = $shape['parent'];
    if (!is_string($parent) || !array_key_exists($parent, $classes)) {
        return [$shape['reqArg'], $shape['fields']];
    }
    [$parentReqArg, $parentFields] = $resolveShape($parent);
    return [
        $shape['reqArg'] || $parentReqArg,
        array_values(array_unique(array_merge($parentFields, $shape['fields']))),
    ];
};

$rows = [];
foreach ($files as $file) {
    $code = pathinfo($file, PATHINFO_FILENAME);
    [$reqArg, $fields] = $resolveShape($code);
    if (!isset($rows[$code])) {
        $rows[$code] = [
            'code' => $code,
            'scopes' => [],
            'reqArg' => $reqArg,
            'fields' => $fields,
            'sources' => [],
        ];
    }
    $rows[$code]['scopes'][] = basename(dirname($file));
    $rows[$code]['sources'][] = str_replace($repoRoot . '/', '', $file);
}
ksort($rows, SORT_STRING);

if (count($rows) !== 92) {
    fwrite(STDERR, 'expected 92 unique PHP commands, got ' . count($rows) . "\n");
    exit(1);
}
foreach ($rows as $row) {
    if ($row['reqArg'] && $row['fields'] === []) {
        fwrite(STDERR, "reqArg command has no captured fields: {$row['code']}\n");
        exit(1);
    }
}

$document = [
    'oracle' => 'legacy/devsam-core PHP command source',
    'fileCount' => count($files),
    'uniqueCommandCount' => count($rows),
    'commands' => array_values($rows),
];
$json = json_encode($document, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
if ($json === false) {
    fwrite(STDERR, "cannot encode matrix\n");
    exit(1);
}
$json .= "\n";

$out = $options['out'] ?? null;
if (is_string($out) && $out !== '') {
    if (file_put_contents($out, $json) === false) {
        fwrite(STDERR, "cannot write {$out}\n");
        exit(1);
    }
} else {
    echo $json;
}

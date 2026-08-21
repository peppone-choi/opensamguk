import { realpathSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const sharedRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const webRoot = resolve(sharedRoot, '..');
const requestedApps = process.argv.slice(2);
const appNames = requestedApps.length > 0 ? requestedApps : ['gateway', 'game'];
const packageRoots = [sharedRoot, ...appNames.map((name) => resolve(webRoot, name))];

const resolution = Object.fromEntries(
  ['react', 'react-dom'].map((moduleName) => [
    moduleName,
    Object.fromEntries(packageRoots.map((packageRoot) => {
      const packageRequire = createRequire(resolve(packageRoot, 'package.json'));
      return [packageRoot, realpathSync(packageRequire.resolve(`${moduleName}/package.json`))];
    })),
  ]),
);

for (const [moduleName, pathsByPackage] of Object.entries(resolution)) {
  const distinctPaths = new Set(Object.values(pathsByPackage));
  if (distinctPaths.size !== 1) {
    throw new Error(`${moduleName} is not a singleton: ${JSON.stringify(pathsByPackage)}`);
  }
}

console.log(JSON.stringify(resolution, null, 2));

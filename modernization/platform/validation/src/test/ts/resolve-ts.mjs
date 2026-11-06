// Lets node --test load the mirror's extensionless relative imports (as the web app's bundler
// resolves them) by trying the specifier with .ts appended.
import { registerHooks } from 'node:module';

registerHooks({
  resolve(specifier, context, nextResolve) {
    if ((specifier.startsWith('./') || specifier.startsWith('../')) && !/\.[cm]?[jt]s$|\.json$/.test(specifier)) {
      try {
        return nextResolve(`${specifier}.ts`, context);
      } catch {
        // fall through to the default resolution and its error
      }
    }
    return nextResolve(specifier, context);
  },
});

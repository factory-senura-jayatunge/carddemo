import { use, useContext, useLayoutEffect } from 'react';
import { UNSAFE_DataRouterContext, useLocation } from 'react-router';

type DataRouter = NonNullable<React.ContextType<typeof UNSAFE_DataRouterContext>>['router'];

const waiting = new WeakMap<DataRouter, { promise: Promise<void>; resolve: () => void }>();

function latestCommitted(router: DataRouter): Promise<void> {
  let entry = waiting.get(router);
  if (!entry) {
    let resolve = () => {};
    const promise = new Promise<void>((done) => {
      resolve = done;
    });
    entry = { promise, resolve };
    waiting.set(router, entry);
  }
  return entry.promise;
}

/**
 * Stops a superseded navigation from reaching the screen. The router applies each navigation in its
 * own React transition, and React finishes a transition render it has already started before it
 * takes a newer one; so a page that renders slowly (its chunk just arrived, a loaded machine) would
 * appear, take the keyboard and run its effects after the user had already gone elsewhere. A render
 * whose location is no longer the router's suspends instead, which keeps what is on screen until the
 * newer navigation commits, and then React renders again with the newest location.
 *
 * Render it after the routes' outlet, so it runs last and sees a navigation that started while the
 * pages were rendering.
 */
export function LatestLocationOnly() {
  const { key } = useLocation();
  const router = useContext(UNSAFE_DataRouterContext)?.router;
  useLayoutEffect(() => {
    if (!router) return;
    const entry = waiting.get(router);
    waiting.delete(router);
    entry?.resolve();
  }, [key, router]);
  if (router && router.state.location.key !== key) use(latestCommitted(router));
  return null;
}

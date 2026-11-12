import type { ReactNode } from 'react';
import type { Features } from '@/api/client';
import { FullPageLoader } from '@/components/FullPageLoader';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { useFeaturesQuery } from './features';

/**
 * The pages of an optional module exist only while GET /api/features reports
 * it installed (web.md, Routes); otherwise the address is an unknown page.
 * Used inside RequireRole, so the features call has a session.
 */
export function RequireModule({ module, children }: { module: keyof Features['modules']; children: ReactNode }) {
  const { data, isError } = useFeaturesQuery();
  if (data?.modules[module]) return children;
  if (data || isError) return <NotFoundPage />;
  return <FullPageLoader />;
}

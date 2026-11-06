import { useQuery } from '@tanstack/react-query';
import { identityApi, type Features } from '@/api/client';
import { unwrap } from '@/api/http';
import { useSession } from './SessionProvider';

/** GET /api/features: installed modules and the UI fidelity switches. */
export function useFeatures(): Features | undefined {
  const { state } = useSession();
  const { data } = useQuery({
    queryKey: ['features'],
    queryFn: () => unwrap(identityApi.GET('/api/features')),
    enabled: state.status === 'authenticated',
    staleTime: Infinity,
  });
  return data;
}

/** A UI switch from the ledger; the fixed behaviour is assumed until features load. */
export function useUiSwitch(itemId: string): 'legacy' | 'fixed' {
  const value = useFeatures()?.uiSwitches[itemId];
  return value === 'legacy' ? 'legacy' : 'fixed';
}

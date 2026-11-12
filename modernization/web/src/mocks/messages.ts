import { catalogue } from '@/lib/messages';
import { messages } from '@/messages/all';

/** The whole catalogue: the mock answers for every service, so it fills any key. */
export { messages };
export const { fillMessage } = catalogue(messages);

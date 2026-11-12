export declare const GROUPS: readonly string[];
export declare const ENTRY_GROUPS: readonly string[];
export declare const CORE_KEYS: readonly string[];
export declare function groupOf(key: string, source: string): string;
export declare function keysOfGroupFile(text: string): string[];
export declare function entriesOfGroupFile(text: string): { key: string; text: string }[];

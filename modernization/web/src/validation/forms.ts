import { RuleTable, type FieldProblem, type RulesFile, type ValidationResult } from '@carddemo/validation/rules';
import lookups from '@carddemo/validation-data/lookups.json';
import rules from '@carddemo/validation-data/rules.json';

export type { FieldProblem, ValidationResult };

/** The services' rule table (platform/validation), so the page and the service run the same edits. */
export const ruleTable = new RuleTable(rules as unknown as RulesFile, lookups);

export type FormId =
  | 'accountView'
  | 'accountUpdateLookup'
  | 'accountUpdate'
  | 'cardSearch'
  | 'cardLookup'
  | 'cardUpdate'
  | 'userAdd'
  | 'userUpdate'
  | 'userAddLegacy'
  | 'userUpdateLegacy'
  | 'signOn'
  | 'passwordChange'
  | 'passwordChangeAnyMode';

export const form = (id: FormId) => ruleTable.form(id);

export { today } from './today';


import { TextInput } from '@mantine/core';
import type { Ref } from 'react';
import type { CardKeyField } from '@/validation/accountRules';
import classes from './records.module.css';

export interface CardKeyValues {
  acctId: string;
  cardNum: string;
}

interface CardKeyFieldsProps {
  values: CardKeyValues;
  onChange?: (values: CardKeyValues) => void;
  /** Keys of a card opened from the list: shown, not typed (BR-CRDV-003). */
  readOnly?: boolean;
  /** The fields in red. */
  invalid: readonly CardKeyField[];
  /** The red fields that get the `*` marker (VAL-CRDV-008); it sits beside the input, never in it. */
  marked: readonly CardKeyField[];
  messageId: string;
  acctRef?: Ref<HTMLInputElement>;
  cardRef?: Ref<HTMLInputElement>;
}

function Marker({ field }: { field: CardKeyField }) {
  return (
    <span className={classes.marker} aria-hidden="true" data-testid={`${field === 'acctId' ? 'acct-id' : 'card-num'}-marker`}>
      *
    </span>
  );
}

/** Account Number (11) and Card Number (16), the keys of card view and card update. */
export function CardKeyFields({ values, onChange, readOnly, invalid, marked, messageId, acctRef, cardRef }: CardKeyFieldsProps) {
  const red = (field: CardKeyField) => invalid.includes(field);
  const describedBy = (field: CardKeyField, hint: string) => [hint, red(field) ? messageId : null].filter(Boolean).join(' ');
  return (
    <>
      <div className={classes.keyGroup}>
        <TextInput
          ref={acctRef}
          label="Account number"
          value={values.acctId}
          onChange={(event) => onChange?.({ ...values, acctId: event.currentTarget.value })}
          readOnly={readOnly}
          maxLength={11}
          inputMode="numeric"
          autoComplete="off"
          spellCheck={false}
          description={<span id="acct-id-hint">{readOnly ? 'From the card you chose.' : '11 digits.'}</span>}
          error={red('acctId') ? true : undefined}
          aria-describedby={describedBy('acctId', 'acct-id-hint')}
          className={classes.acctField}
          classNames={{ input: classes.keyInput }}
          data-testid="acct-id"
        />
        {marked.includes('acctId') && <Marker field="acctId" />}
      </div>
      <div className={classes.keyGroup}>
        <TextInput
          ref={cardRef}
          label="Card number"
          value={values.cardNum}
          onChange={(event) => onChange?.({ ...values, cardNum: event.currentTarget.value })}
          readOnly={readOnly}
          maxLength={16}
          inputMode="numeric"
          autoComplete="off"
          spellCheck={false}
          description={<span id="card-num-hint">{readOnly ? 'Masked; only the last four digits are shown.' : '16 digits, no spaces.'}</span>}
          error={red('cardNum') ? true : undefined}
          aria-describedby={describedBy('cardNum', 'card-num-hint')}
          className={classes.cardField}
          classNames={{ input: classes.keyInput }}
          data-testid="card-num"
        />
        {marked.includes('cardNum') && <Marker field="cardNum" />}
      </div>
    </>
  );
}

/** The red key fields for a service problem (contract §8 Web behaviour, "Errors"). */
export function keyFieldsOf(code: string): CardKeyField[] {
  switch (code) {
    case 'NO_INPUT':
    case 'CARD_NOT_FOUND':
      return ['acctId', 'cardNum'];
    case 'CARD_NUM_REQUIRED':
    case 'INVALID_CARD_NUM':
      return ['cardNum'];
    default:
      return ['acctId'];
  }
}

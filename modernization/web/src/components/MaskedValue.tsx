import { Button, Group } from '@mantine/core';
import classes from './MaskedValue.module.css';

interface MaskedValueProps {
  /** The value as the service sent it, masked to its last four characters. */
  masked: string;
  /** The clear value after an audited Show, else undefined. */
  clear?: string | null;
  /** The field's label, for the Show and Hide buttons' names. */
  label: string;
  /** Shown only to ADMIN (D-09): calls the unmask endpoint for this one field. */
  onShow?: () => void;
  onHide?: () => void;
  loading?: boolean;
  testId?: string;
}

/**
 * A masked value (D-09) with, for an ADMIN, a Show control that reveals this
 * one field through the audited unmask endpoint, and Hide to mask it again.
 */
export function MaskedValue({ masked, clear, label, onShow, onHide, loading, testId }: MaskedValueProps) {
  const shown = typeof clear === 'string';
  const value = shown ? clear : masked;
  return (
    <Group gap={8} wrap="nowrap" className={classes.root}>
      <span className="cd-mono" data-testid={testId} data-masked={shown ? undefined : true}>
        {value || '\u2014'}
      </span>
      {onShow && masked && (
        <Button
          size="compact-xs"
          variant="subtle"
          onClick={shown ? onHide : onShow}
          loading={loading}
          aria-label={`${shown ? 'Hide' : 'Show'} ${label}`}
          data-testid={testId ? `${testId}-toggle` : undefined}
        >
          {shown ? 'Hide' : 'Show'}
        </Button>
      )}
    </Group>
  );
}

import { Button, Text, TextInput } from '@mantine/core';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';
import { transactionApi, type TransactionView } from '@/api/client';
import { unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MaskedValue } from '@/components/MaskedValue';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { useUnexpected } from '@/components/UnexpectedError';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { formatCurrency } from '@/lib/format';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { transactionMessages } from '@/messages/transaction';
import { readerHome, useBack } from '@/lib/useBack';
import { problemMessage, useReveal } from '@/lib/useReveal';
import { useUser } from '@/session/SessionProvider';
import { Fact } from './RecordParts';
import { TRANSACTIONS_PATH } from './TransactionListPage';
import classes from './records.module.css';
import tx from './TransactionPages.module.css';

const { message } = catalogue(transactionMessages);

type Reveal = ReturnType<typeof useReveal>;

/** The transaction as the service sends it, the card number masked until an ADMIN shows it. */
export function TransactionDetails({ tran, reveal, onShow }: { tran: TransactionView; reveal: Reveal; onShow?: () => void }) {
  return (
    <section className={`${classes.panel} ${classes.panelWide}`} aria-labelledby="tran-panel-title" data-testid="tran-panel">
      <div className={tx.summary}>
        <div>
          <p className={classes.panelKicker}>Transaction</p>
          <h2 id="tran-panel-title" className={classes.panelTitle} data-testid="tran-desc">
            {tran.description || '\u2014'}
          </h2>
        </div>
        <div>
          <p className={tx.amount}>{formatCurrency(tran.amount)}</p>
          <span className={classes.legacy} title="Mainframe format" data-testid="tran-amount">
            {tran.amountDisplay}
          </span>
        </div>
      </div>
      <dl className={classes.facts}>
        <Fact label="Transaction ID" testId="tran-id">
          <span className="cd-mono">{tran.tranId}</span>
        </Fact>
        <Fact label="Card number">
          <MaskedValue
            masked={tran.cardNum}
            clear={reveal.clear.cardNum}
            label="card number"
            onShow={onShow}
            onHide={() => reveal.hide('cardNum')}
            loading={reveal.loading === 'cardNum'}
            testId="tran-card"
          />
        </Fact>
        <Fact label="Type code" testId="tran-type">
          <span className="cd-mono">{tran.typeCd}</span>
        </Fact>
        <Fact label="Category code" testId="tran-cat">
          <span className="cd-mono">{tran.catCd}</span>
        </Fact>
        <Fact label="Source" testId="tran-source">
          {tran.source}
        </Fact>
        <Fact label="Original time" testId="tran-orig">
          <span className="cd-mono">{tran.origTs}</span>
        </Fact>
        <Fact label="Processed time" testId="tran-proc">
          <span className="cd-mono">{tran.procTs}</span>
        </Fact>
      </dl>
      <h3 className={classes.sectionLabel}>Merchant</h3>
      <dl className={classes.facts}>
        <Fact label="Merchant ID" testId="tran-merchant-id">
          <span className="cd-mono">{tran.merchantId}</span>
        </Fact>
        <Fact label="Name" testId="tran-merchant-name">
          {tran.merchantName}
        </Fact>
        <Fact label="City" testId="tran-merchant-city">
          {tran.merchantCity}
        </Fact>
        <Fact label="ZIP code" testId="tran-merchant-zip">
          <span className="cd-mono">{tran.merchantZip}</span>
        </Fact>
      </dl>
    </section>
  );
}

function EmptyTransaction() {
  return (
    <section className={`${classes.panel} ${classes.panelWide}`} data-empty aria-labelledby="tran-panel-title" data-testid="tran-panel-empty">
      <h2 id="tran-panel-title" className={classes.panelTitle}>
        Transaction details
      </h2>
      <Text size="sm" c="dimmed" mt={6}>
        Enter a transaction ID, then choose View.
      </Text>
    </section>
  );
}

/**
 * COTRN01C as a web page. /transactions/lookup asks for an ID (menu option 7);
 * /transactions/:tranId shows the one chosen on the list without Enter (BR-TRNV-003).
 * Either way another ID can be typed and viewed.
 */
export function TransactionViewPage() {
  const { tranId } = useParams();
  const fromList = tranId !== undefined;
  const user = useUser();
  const isAdmin = user.role === 'ADMIN';
  const navigate = useNavigate();
  const back = useBack(fromList ? TRANSACTIONS_PATH : readerHome(user));
  const [typed, setTyped] = useState(tranId ?? '');
  const [tran, setTran] = useState<TransactionView | null>(null);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [invalid, setInvalid] = useState(false);
  // From the list the transaction is on its way from the first render.
  const [busy, setBusy] = useState(fromList);
  const idRef = useRef<HTMLInputElement>(null);
  const busyRef = useRef(fromList);

  const unexpected = useUnexpected();
  const onRevealError = useCallback(
    (error: unknown) => {
      if (!unexpected(error)) setScreenMessage(problemMessage(error));
    },
    [unexpected],
  );
  const reveal = useReveal(onRevealError);

  /** VAL-TRNV-002: a failed lookup keeps nothing of the transaction shown before. */
  const fail = (next: ScreenMessage) => {
    reveal.reset();
    setTran(null);
    setInvalid(true);
    setScreenMessage(next);
    window.requestAnimationFrame(() => idRef.current?.focus());
  };

  /** One viewTransaction call, with busy already set. */
  const read = (id: string) =>
    unwrap(transactionApi.GET('/api/transactions/{tranId}', { params: { path: { tranId: id } } }))
      .then((result) => {
        reveal.reset();
        setTran(result);
        setInvalid(false);
        setScreenMessage(null);
      })
      .catch((error: unknown) => {
        if (!unexpected(error)) fail(problemMessage(error));
      })
      .finally(() => {
        busyRef.current = false;
        setBusy(false);
      });

  const lookup = (id: string) => {
    if (busyRef.current) return;
    if (id.replace(/ +$/, '') === '') {
      fail(message('TRAN_ID_REQUIRED'));
      return;
    }
    busyRef.current = true;
    setBusy(true);
    void read(id);
  };

  useEffect(() => {
    if (tranId !== undefined) void read(tranId);
    else idRef.current?.focus();
    // Only a new tranId in the address reads again.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tranId]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    lookup(typed);
  };

  const clear = () => {
    setTyped('');
    reveal.reset();
    setTran(null);
    setInvalid(false);
    setScreenMessage(null);
    idRef.current?.focus();
  };

  const browse = () => void navigate(TRANSACTIONS_PATH);

  const onShow = () => {
    if (!tran) return;
    const id = tran.tranId;
    void reveal.show('cardNum', () =>
      unwrap(transactionApi.POST('/api/transactions/{tranId}/unmask', { params: { path: { tranId: id } }, body: { fields: ['cardNum'] } })),
    );
  };

  usePageKeys({
    back,
    clear,
    save: browse,
    escapeBack: true,
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const messageId = 'tran-view-message';
  return (
    <>
      <PageTitle
        title="Transaction"
        description={fromList ? 'The transaction you chose on the list. Type another ID to view it instead.' : 'Look up a transaction by its ID.'}
        actions={
          <>
            <Button variant="default" onClick={browse} data-testid="browse">
              Transaction list
              <KeyHint name="F5" />
            </Button>
            <Button variant="default" onClick={back} data-testid="back">
              {fromList ? 'Back to list' : 'Back'}
              <KeyHint name="F3" />
            </Button>
          </>
        }
      />
      <form className={classes.searchCard} onSubmit={submit} noValidate aria-label="Transaction lookup">
        <div className={classes.searchRow}>
          <TextInput
            ref={idRef}
            label="Transaction ID"
            value={typed}
            onChange={(event) => setTyped(event.currentTarget.value)}
            inputMode="numeric"
            autoComplete="off"
            spellCheck={false}
            description={<span id="tran-id-hint">Up to 16 digits; leading zeros are optional.</span>}
            error={invalid ? true : undefined}
            aria-describedby={['tran-id-hint', invalid ? messageId : null].filter(Boolean).join(' ')}
            className={tx.searchField}
            classNames={{ input: classes.keyInput }}
            data-testid="tran-id-input"
          />
          <div className={classes.searchActions}>
            <Button type="submit" loading={busy} data-testid="submit">
              View
            </Button>
            <Button variant="subtle" color="slate" onClick={clear} disabled={busy} data-testid="clear">
              Clear
              <KeyHint name="F4" />
            </Button>
          </div>
        </div>
      </form>
      <div className={classes.messageSlot}>
        <MessageArea id={messageId} message={screenMessage} />
      </div>
      <div className={classes.panels}>
        {tran ? <TransactionDetails tran={tran} reveal={reveal} onShow={isAdmin ? onShow : undefined} /> : !busy && <EmptyTransaction />}
      </div>
    </>
  );
}

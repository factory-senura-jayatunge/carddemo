import { Button, Text } from '@mantine/core';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useParams } from 'react-router';
import { accountApi, type CardView } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MaskedValue } from '@/components/MaskedValue';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { useUnexpected } from '@/components/UnexpectedError';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { cardMessages } from '@/messages/card';
import { readerHome, useBack } from '@/lib/useBack';
import { problemMessage, useReveal } from '@/lib/useReveal';
import { readCurrentAccount, setCurrentAccount } from '@/session/currentAccount';
import { useUser } from '@/session/SessionProvider';
import { checkCardKeys, type CardKeyField } from '@/validation/accountRules';
import { CardKeyFields, keyFieldsOf, type CardKeyValues } from './CardKeyFields';
import { Fact, Flag } from './RecordParts';
import classes from './records.module.css';

const { message } = catalogue(cardMessages);

type Reveal = ReturnType<typeof useReveal>;

/** The store keeps the customer only while it still belongs to the account shown. */
export function rememberCard(acctId: string, cardId: string): void {
  const current = readCurrentAccount();
  setCurrentAccount({ acctId, custId: current?.acctId === acctId ? current.custId : null, cardId });
}

/** The card as a card: masked number, name, expiry and status. */
export function CardFace({ card, reveal, onShow }: { card: CardView; reveal?: Reveal; onShow?: () => void }) {
  const active = card.activeStatus === 'Y';
  return (
    <section className={classes.panel} aria-labelledby="card-panel-title" data-testid="card-panel">
      <div className={classes.panelHeader}>
        <div>
          <p className={classes.panelKicker}>Credit card</p>
          <h2 id="card-panel-title" className={classes.panelTitle} data-testid="card-name">
            {card.embossedName || '\u2014'}
          </h2>
        </div>
        <Flag value={card.activeStatus} yes="Active" no="Inactive" testId="card-active" />
      </div>
      <div className={classes.cardFace} data-inactive={active ? undefined : true} aria-hidden="true">
        <span className={classes.cardFaceNumber}>{reveal?.clear.cardNum ?? card.cardNum}</span>
        <div className={classes.cardFaceRow}>
          <span>
            <span className={classes.cardFaceLabel}>Card holder</span>
            <span className={classes.cardFaceValue}>{card.embossedName}</span>
          </span>
          <span>
            <span className={classes.cardFaceLabel}>Expires</span>
            <span className={`${classes.cardFaceValue} cd-mono`}>
              {card.expiryMonth}/{card.expiryYear}
            </span>
          </span>
        </div>
      </div>
      <dl className={classes.facts} style={{ marginTop: 20 }}>
        <Fact label="Card number" wide>
          <MaskedValue
            masked={card.cardNum}
            clear={reveal?.clear.cardNum}
            label="card number"
            onShow={onShow}
            onHide={() => reveal?.hide('cardNum')}
            loading={reveal?.loading === 'cardNum'}
            testId="card-num-shown"
          />
        </Fact>
        <Fact label="Account" testId="card-acct">
          {card.acctId}
        </Fact>
        <Fact label="Expiry date">
          <span className="cd-mono">
            <span data-testid="card-expiry-month">{card.expiryMonth}</span> / <span data-testid="card-expiry-year">{card.expiryYear}</span>
          </span>
        </Fact>
      </dl>
    </section>
  );
}

function EmptyCard() {
  return (
    <section className={classes.panel} data-empty aria-labelledby="card-panel-title" data-testid="card-panel-empty">
      <h2 id="card-panel-title" className={classes.panelTitle}>
        Card details
      </h2>
      <Text size="sm" c="dimmed" mt={6}>
        Enter an account number and one of its card numbers, then choose View.
      </Text>
    </section>
  );
}

const EMPTY: CardKeyValues = { acctId: '', cardNum: '' };

/**
 * COCRDSLC as a web page. /cards/lookup takes typed keys (menu option 4);
 * /cards/:cardId shows the card chosen on the list, with its keys read-only.
 */
export function CardViewPage() {
  const { cardId } = useParams();
  const fromList = cardId !== undefined;
  const user = useUser();
  const isAdmin = user.role === 'ADMIN';
  const back = useBack(fromList ? '/cards' : readerHome(user));
  const [keys, setKeys] = useState<CardKeyValues>(EMPTY);
  const [card, setCard] = useState<CardView | null>(null);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const [invalid, setInvalid] = useState<CardKeyField[]>([]);
  const [marked, setMarked] = useState<CardKeyField[]>([]);
  const [busy, setBusy] = useState(false);
  const acctRef = useRef<HTMLInputElement>(null);
  const cardRef = useRef<HTMLInputElement>(null);
  const submitRef = useRef<HTMLButtonElement>(null);

  const focus = useCallback(
    (field: CardKeyField | 'submit') => {
      window.requestAnimationFrame(() => (field === 'submit' ? submitRef : field === 'acctId' ? acctRef : cardRef).current?.focus());
    },
    [],
  );
  const show = useCallback(
    (next: ScreenMessage | null, red: CardKeyField[] = [], mark: CardKeyField[] = [], field?: CardKeyField) => {
      setScreenMessage(next);
      setInvalid(red);
      setMarked(mark);
      focus(field ?? (fromList ? 'submit' : 'acctId'));
    },
    [focus, fromList],
  );
  const unexpected = useUnexpected();
  const onRevealError = useCallback(
    (error: unknown) => {
      if (!unexpected(error)) setScreenMessage(problemMessage(error));
    },
    [unexpected],
  );
  const reveal = useReveal(onRevealError);

  const shown = useCallback(
    (result: CardView) => {
      reveal.reset();
      setCard(result);
      if (fromList) setKeys({ acctId: result.acctId, cardNum: result.cardNum });
      rememberCard(result.acctId, result.cardId);
      show(null);
    },
    [fromList, reveal, show],
  );

  const failed = useCallback(
    (error: unknown) => {
      if (unexpected(error)) return;
      reveal.reset();
      setCard(null);
      const red = error instanceof ApiError && !error.isNetwork ? keyFieldsOf(error.code) : [];
      const field = error instanceof ApiError && error.field === 'cardNum' ? 'cardNum' : 'acctId';
      show(problemMessage(error), fromList ? [] : red, [], field);
    },
    [fromList, reveal, show, unexpected],
  );

  const busyRef = useRef(false);
  const run = async (step: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      await step();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const readById = async () => {
    if (cardId === undefined) return;
    const id = cardId;
    await run(async () => {
      try {
        shown(await unwrap(accountApi.GET('/api/cards/{cardId}', { params: { path: { cardId: id } } })));
      } catch (error) {
        failed(error);
      }
    });
  };

  useEffect(() => {
    void readById();
    // Only a new cardId reads again; Refresh calls readById itself.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cardId]);

  // The typed-keys path opens with focus on Account Number (CRDV S1); show() only focuses after an action.
  useEffect(() => {
    if (!fromList) acctRef.current?.focus();
  }, [fromList]);

  const lookup = async () => {
    const problem = checkCardKeys(keys.acctId, keys.cardNum);
    if (problem) {
      reveal.reset();
      setCard(null);
      show(problem.message, problem.invalid, problem.invalid, problem.field);
      return;
    }
    await run(async () => {
      try {
        shown(await unwrap(accountApi.POST('/api/cards/lookup', { body: { acctId: keys.acctId, cardNum: keys.cardNum } })));
      } catch (error) {
        failed(error);
      }
    });
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    void (fromList ? readById() : lookup());
  };

  const clear = () => {
    setKeys(EMPTY);
    reveal.reset();
    setCard(null);
    show(null);
  };

  const onShow = () => {
    if (!card) return;
    const id = card.cardId;
    void reveal.show('cardNum', () => unwrap(accountApi.POST('/api/cards/{cardId}/unmask', { params: { path: { cardId: id } }, body: { fields: ['cardNum'] } })));
  };

  usePageKeys({
    back,
    clear: fromList ? undefined : clear,
    escapeBack: true,
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const messageId = 'card-view-message';
  const missing = fromList && !card && !busy && screenMessage !== null;
  const infoLine = card ? card.message : message('CARD_KEYS_PROMPT').text;
  return (
    <>
      <PageTitle
        title="Credit card"
        description={
          fromList
            ? 'The card you chose on the card list.'
            : 'Look up a card by its account number and card number.'
        }
        actions={
          <Button variant="default" onClick={back}>
            {fromList ? 'Back to card list' : 'Back'}
            <KeyHint name="F3" />
          </Button>
        }
      />
      <form className={classes.searchCard} onSubmit={submit} noValidate aria-describedby="card-view-info">
        <div className={classes.searchRow}>
          <CardKeyFields
            values={keys}
            onChange={fromList ? undefined : setKeys}
            readOnly={fromList}
            invalid={invalid}
            marked={marked}
            messageId={messageId}
            acctRef={acctRef}
            cardRef={cardRef}
          />
          <div className={classes.searchActions}>
            {!missing && (
              <Button ref={submitRef} type="submit" loading={busy} data-testid="submit">
                {fromList ? 'Refresh' : 'View'}
              </Button>
            )}
            {!fromList && (
              <Button variant="subtle" color="slate" onClick={clear} disabled={busy}>
                Clear
                <KeyHint name="F4" />
              </Button>
            )}
          </div>
        </div>
        <p id="card-view-info" className={classes.infoLine} data-testid="info-line">
          {infoLine}
        </p>
      </form>
      <div className={classes.messageSlot}>
        <MessageArea id={messageId} message={screenMessage} />
      </div>
      <div className={classes.panels}>
        {card ? <CardFace card={card} reveal={reveal} onShow={isAdmin ? onShow : undefined} /> : !fromList && <EmptyCard />}
      </div>
    </>
  );
}

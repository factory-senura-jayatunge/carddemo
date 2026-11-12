import { Button, Loader, Text } from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { authorizationApi, type AuthorizationDetail } from '@/api/client';
import { ApiError, unwrap } from '@/api/http';
import { KeyHint } from '@/components/KeyHint';
import { MessageArea } from '@/components/MessageArea';
import { PageTitle } from '@/components/PageTitle';
import { useUnexpected } from '@/components/UnexpectedError';
import { usePageKeys } from '@/keys/KeyboardShortcuts';
import { approvalLabel, matchLabel } from '@/lib/authorizationDisplay';
import { formatCurrency } from '@/lib/format';
import { catalogue, type ScreenMessage } from '@/lib/messages';
import { authorizationMessages } from '@/messages/authorization';
import { useBack } from '@/lib/useBack';
import { problemMessage } from '@/lib/useReveal';
import { readCurrentAccount, setCurrentAccount } from '@/session/currentAccount';
import { Coded, Fact } from './RecordParts';
import classes from './records.module.css';
import auth from './AuthorizationsPage.module.css';

const { message } = catalogue(authorizationMessages);

const FRAUD_NOTE: Record<string, string> = { F: 'Reported as fraud', R: 'Fraud report removed' };

function remember(detail: AuthorizationDetail) {
  const current = readCurrentAccount();
  if (current?.acctId === detail.acctId) return;
  setCurrentAccount({ acctId: detail.acctId, custId: null, cardId: null });
}

/** COPAUS1C (detail) and COPAUS2C (fraud marking) as one web page; each authorization starts with fresh state (D-75). */
export function AuthorizationDetailPage() {
  const { authId = '' } = useParams();
  return <AuthorizationDetail key={authId} authId={authId} />;
}

function AuthorizationDetail({ authId }: { authId: string }) {
  const location = useLocation();
  const navigate = useNavigate();
  const unexpected = useUnexpected();
  const back = useBack('/authorizations');
  const [detail, setDetail] = useState<AuthorizationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [screenMessage, setScreenMessage] = useState<ScreenMessage | null>(null);
  const savingRef = useRef(false);
  const fraudRef = useRef<HTMLButtonElement>(null);
  const backRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    let stale = false;
    unwrap(authorizationApi.GET('/api/authorizations/{authId}', { params: { path: { authId } } }))
      .then((found) => {
        if (stale) return;
        setDetail(found);
        remember(found);
      })
      .catch((error: unknown) => {
        if (stale || unexpected(error)) return;
        // DEF-AUDT-001, DEF-AUDT-004: no detail is shown after a failed read.
        setScreenMessage(problemMessage(error));
      })
      .finally(() => {
        if (!stale) setLoading(false);
      });
    return () => {
      stale = true;
    };
  }, [authId, unexpected]);

  useEffect(() => {
    if (loading) return;
    (detail ? fraudRef : backRef).current?.focus();
  }, [loading, detail]);

  /** BR-AUDT-006: the next older authorization of the account; the end is the web's own message (VAL-AUDT-003). */
  const next = () => {
    if (!detail || savingRef.current) return;
    if (detail.nextOlderAuthId === null) {
      setScreenMessage(message('AUTH_LAST'));
      return;
    }
    void navigate(`/authorizations/${detail.nextOlderAuthId}`, { replace: true, state: location.state as unknown });
  };

  /** BR-AUDT-007: F becomes R, anything else F, written against the version shown. */
  const toggleFraud = async () => {
    if (!detail || savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    try {
      const result = await unwrap(
        authorizationApi.POST('/api/authorizations/{authId}/fraud', {
          params: { path: { authId: String(detail.authId) }, header: { 'If-Match': `"${detail.version}"` } },
          body: { action: detail.fraud.flag === 'F' ? 'R' : 'F' },
        }),
      );
      setDetail(result.detail);
      setScreenMessage({ text: result.message, tone: result.severity });
    } catch (error) {
      if (unexpected(error)) return;
      if (error instanceof ApiError && error.code === 'RECORD_CHANGED') {
        // D-17: the stored detail replaces the one shown; the user decides again on it.
        const current = error.body.current as AuthorizationDetail | undefined;
        if (current) setDetail(current);
      } else if (error instanceof ApiError && error.code === 'AUTHORIZATION_NOT_FOUND') {
        setDetail(null);
      }
      // FRAUD_UPDATE_FAILED and the read failures keep the stored status shown (DEF-AUDT-003, DEF-AUDT-005).
      setScreenMessage(problemMessage(error));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  usePageKeys({
    back,
    escapeBack: true,
    save: detail ? () => void toggleFraud() : undefined,
    next: detail ? next : undefined,
    invalidKey: () => setScreenMessage(message('INVALID_KEY')),
  });

  const flagged = detail?.fraud.flag === 'F';
  return (
    <>
      <PageTitle
        title="Authorization details"
        description="One authorization request with its decision. Report it as fraud if the cardholder did not make it."
        actions={
          <Button ref={backRef} variant="default" onClick={back}>
            Back
            <KeyHint name="F3" />
          </Button>
        }
      />
      <MessageArea id="authorization-message" message={screenMessage} />
      {loading && (
        <div className={classes.messageSlot} aria-live="polite" data-testid="auth-loading">
          <Loader size="sm" aria-hidden="true" /> <Text component="span" size="sm" c="dimmed">Loading the authorization…</Text>
        </div>
      )}
      {detail && (
        <div className={classes.panels} data-testid="auth-detail">
          <section className={`${classes.panel} ${classes.panelWide}`} aria-labelledby="auth-detail-title">
            <div className={classes.panelHeader}>
              <div>
                <p className={classes.panelKicker}>Transaction</p>
                <h2 id="auth-detail-title" className={`${classes.panelTitle} cd-mono`} data-testid="detail-tran">
                  {detail.transactionId}
                </h2>
              </div>
              <Coded
                value={detail.approval}
                note={approvalLabel(detail.approval)}
                tone={detail.approval === 'A' ? 'good' : 'bad'}
                testId="detail-approval"
              />
            </div>
            <div className={`${classes.balance} ${auth.decision}`}>
              <div>
                <Text size="sm" c="dimmed" fw={560}>
                  Approved amount
                </Text>
                <p className={classes.balanceAmount} data-testid="detail-approved-amount">
                  {formatCurrency(detail.approvedAmount)}
                </p>
              </div>
              <div>
                <Text size="sm" c="dimmed" fw={560}>
                  Reason
                </Text>
                <p className={`${auth.reason} cd-mono`} data-testid="detail-reason">
                  {detail.reasonText}
                </p>
              </div>
            </div>
            <dl className={classes.facts}>
              <Fact label="Requested">
                <span className="cd-mono" data-testid="detail-date">
                  {detail.requestDate}
                </span>{' '}
                <span className="cd-mono" data-testid="detail-time">
                  {detail.requestTime}
                </span>
              </Fact>
              <Fact label="Transaction amount" testId="detail-amount">
                {formatCurrency(detail.transactionAmount)}
              </Fact>
              <Fact label="Account">
                <span className="cd-mono" data-testid="detail-acct">
                  {detail.acctId}
                </span>
              </Fact>
              <Fact label="Card number">
                <span className="cd-mono" data-testid="detail-card" data-masked>
                  {detail.cardNum}
                </span>
              </Fact>
              <Fact label="Card expiry">
                <span className="cd-mono" data-testid="detail-expiry">
                  {detail.cardExpiry}
                </span>
              </Fact>
              <Fact label="Authorization code">
                <span className="cd-mono" data-testid="detail-auth-code">
                  {detail.authIdCode}
                </span>
              </Fact>
              <Fact label="Response code">
                <span className="cd-mono" data-testid="detail-resp">
                  {detail.respCode}
                </span>
              </Fact>
              <Fact label="Type" testId="detail-type">
                {detail.authType}
              </Fact>
              <Fact label="Match status">
                <Coded value={detail.matchStatus} note={matchLabel(detail.matchStatus)} testId="detail-match" />
                {detail.matchedTranId && (
                  <span className={classes.current}>
                    Transaction <span className="cd-mono" data-testid="detail-matched-tran">{detail.matchedTranId}</span>
                  </span>
                )}
              </Fact>
              <Fact label="Processing code">
                <span className="cd-mono" data-testid="detail-processing">
                  {detail.processingCode}
                </span>
              </Fact>
              <Fact label="POS entry mode">
                <span className="cd-mono" data-testid="detail-pos">
                  {detail.posEntryMode}
                </span>
              </Fact>
              <Fact label="Source">
                <span className={`cd-mono ${auth.keepSpaces}`} data-testid="detail-source">
                  {detail.messageSource}
                </span>
              </Fact>
              <Fact label="Merchant category">
                <span className="cd-mono" data-testid="detail-mcc">
                  {detail.merchantCategoryCode}
                </span>
              </Fact>
            </dl>
          </section>
          <section className={classes.panel} aria-labelledby="auth-merchant-title" data-testid="detail-merchant">
            <div className={classes.panelHeader}>
              <div>
                <p className={classes.panelKicker}>Merchant</p>
                <h2 id="auth-merchant-title" className={`${classes.panelTitle} ${auth.keepSpaces}`} data-testid="merchant-name">
                  {detail.merchant.name}
                </h2>
              </div>
            </div>
            <dl className={classes.facts}>
              <Fact label="Merchant ID">
                <span className="cd-mono" data-testid="merchant-id">
                  {detail.merchant.id}
                </span>
              </Fact>
              <Fact label="City" testId="merchant-city">
                {detail.merchant.city}
              </Fact>
              <Fact label="State" testId="merchant-state">
                {detail.merchant.state}
              </Fact>
              <Fact label="ZIP" testId="merchant-zip">
                {detail.merchant.zip}
              </Fact>
            </dl>
          </section>
          <section className={classes.panel} aria-labelledby="auth-fraud-title" data-testid="detail-fraud" data-flagged={flagged || undefined}>
            <div className={classes.panelHeader}>
              <div>
                <p className={classes.panelKicker}>Fraud</p>
                <h2 id="auth-fraud-title" className={classes.panelTitle}>
                  {detail.fraud.flag ? FRAUD_NOTE[detail.fraud.flag] ?? 'Fraud status' : 'Not reported'}
                </h2>
              </div>
              <span className="cd-mono" data-testid="detail-fraud-display">
                {detail.fraud.display}
              </span>
            </div>
            <Text size="sm" c="dimmed">
              {flagged
                ? 'New requests on this card are declined while the report stands.'
                : 'Reporting fraud declines new requests on this card until the report is removed.'}
            </Text>
            <div className={classes.formActions}>
              <Button
                ref={fraudRef}
                color={flagged ? undefined : 'red'}
                variant={flagged ? 'default' : 'filled'}
                onClick={() => void toggleFraud()}
                loading={saving}
                data-testid="fraud-toggle"
              >
                {flagged ? 'Remove fraud report' : 'Report fraud'}
                <KeyHint name="F5" />
              </Button>
            </div>
          </section>
          <div className={`${classes.panelWide} ${auth.detailNav}`}>
            <Button variant="default" onClick={next} disabled={saving} data-testid="next">
              Next older authorization
              <KeyHint name="F8" />
            </Button>
          </div>
        </div>
      )}
    </>
  );
}

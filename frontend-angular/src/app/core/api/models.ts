/**
 * Tipi del contratto API (docs/api/openapi.yaml). Sono lo specchio degli schemi OpenAPI:
 * se il contratto cambia, si cambia qui e il compilatore segnala tutti i punti da aggiornare.
 *
 * Convenzioni del contratto: JSON in camelCase, date ISO-8601 UTC (stringhe), importi come
 * STRINGA decimale ("50.00") per evitare errori di virgola mobile.
 */

export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED';
export type ReimbursementStatus = 'OPEN' | 'APPROVED' | 'REJECTED' | 'EXECUTED';

/** Un PDF caricato. Si chiama IndexedDocument per non confondersi con il `Document` del DOM. */
export interface IndexedDocument {
  id: string;
  filename: string;
  sizeBytes: number;
  pageCount: number | null;
  chunkCount: number;
  status: DocumentStatus;
  errorMessage: string | null;
  createdAt: string;
}

export interface DocumentList {
  items: IndexedDocument[];
}

export interface ChatRequest {
  message: string;
  conversationId?: string | null;
  /** Se valorizzato restringe il retrieval a questi documenti; null/assente = tutti. */
  documentIds?: string[] | null;
}

/** Un frammento di documento usato dall'IA per rispondere (citazione). */
export interface Source {
  chunkId: string;
  documentId: string;
  filename: string;
  /** Numero di pagina, base 1. */
  page: number;
  /** Similarità coseno 0..1. */
  score: number;
  snippet: string;
}

/** Azione eseguita dall'IA tramite tool calling: creazione di un rimborso o aggiornamento del suo stato. */
export interface ChatAction {
  type: 'REIMBURSEMENT_CREATED' | 'REIMBURSEMENT_UPDATED';
  reimbursementId: string;
  /** Numero progressivo del rimborso (#1, #2...). */
  number: number;
  amount: string;
  currency: string;
  description: string | null;
  /** Stato del rimborso dopo l'azione. */
  status: ReimbursementStatus;
}

export interface ChatResponse {
  conversationId: string;
  messageId: string;
  answer: string;
  sources: Source[];
  actions: ChatAction[];
  /** Avviso da mostrare a video (es. manca il verbo esplicito); null se non serve. */
  warning: string | null;
  createdAt: string;
}

export interface Reimbursement {
  id: string;
  /** Numero progressivo (#1, #2...): è quello che l'utente scrive in chat. */
  number: number;
  amount: string;
  currency: string;
  description: string | null;
  status: ReimbursementStatus;
  requestedBy: string;
  conversationId: string | null;
  createdAt: string;
}

export interface ReimbursementPage {
  items: Reimbursement[];
  /** Base 0. */
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** Errore RFC 9457 (application/problem+json) restituito da entrambi i backend. */
export interface Problem {
  /** URN stabile, es. "urn:eaa:error:duplicate-document". */
  type: string;
  title: string;
  status: number;
  detail: string;
  errors?: { field: string; message: string }[];
}

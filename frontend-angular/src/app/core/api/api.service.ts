import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { BackendStore } from '../state/backend.store';
import {
  ChatRequest,
  ChatResponse,
  DocumentList,
  IndexedDocument,
  ReimbursementPage,
  ReimbursementStatus,
} from './models';

/**
 * Client HTTP del contratto API. Un metodo per endpoint, nessuna logica: restituisce Observable
 * tipizzati. La gestione dello stato e degli errori è negli store (core/state).
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly backend = inject(BackendStore);

  /** URL base del backend selezionato, letto a ogni chiamata: cambiare backend vale subito. */
  private get baseUrl(): string {
    return this.backend.apiUrl();
  }

  /** GET /api/documents */
  listDocuments(): Observable<IndexedDocument[]> {
    return this.http.get<DocumentList>(`${this.baseUrl}/documents`).pipe(map((r) => r.items));
  }

  /**
   * POST /api/documents (multipart/form-data, campo "file").
   * L'indicizzazione è sincrona (D7): la risposta arriva a PDF indicizzato, può richiedere secondi.
   * Non si imposta Content-Type a mano: il browser aggiunge il boundary del multipart.
   */
  uploadDocument(file: File): Observable<IndexedDocument> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<IndexedDocument>(`${this.baseUrl}/documents`, body);
  }

  /** POST /api/chat */
  chat(request: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.baseUrl}/chat`, request);
  }

  /** GET /api/reimbursements (page base 0, size 1-100). */
  listReimbursements(
    options: { page?: number; size?: number; status?: ReimbursementStatus } = {},
  ): Observable<ReimbursementPage> {
    let params = new HttpParams();
    if (options.page !== undefined) params = params.set('page', options.page);
    if (options.size !== undefined) params = params.set('size', options.size);
    if (options.status) params = params.set('status', options.status);

    return this.http.get<ReimbursementPage>(`${this.baseUrl}/reimbursements`, { params });
  }
}

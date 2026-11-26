import { ChangeDetectionStrategy, Component } from '@angular/core';
import { environment } from '../environments/environment';
import { BackendSwitch } from './features/backend/backend-switch';
import { ChatPanel } from './features/chat/chat-panel';
import { DocumentsPanel } from './features/documents/documents-panel';
import { ReimbursementsPanel } from './features/reimbursements/reimbursements-panel';

/**
 * Shell dell'app: intestazione + dashboard a tre colonne.
 *
 *   sinistra: Documenti   |   centro: Chat   |   destra: Richieste di rimborso
 *
 * Non c'è login né router: l'app opera direttamente come utente demo (environment.demoUser).
 */
@Component({
  selector: 'app-root',
  imports: [BackendSwitch, DocumentsPanel, ChatPanel, ReimbursementsPanel],
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly user = environment.demoUser;
}

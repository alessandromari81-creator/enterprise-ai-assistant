import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type BadgeTone = 'green' | 'amber' | 'red' | 'blue' | 'slate';

/**
 * Etichetta colorata (es. "Indicizzato", "In attesa").
 * I nomi delle classi sono scritti per intero: Tailwind li trova solo se compaiono letteralmente nel codice.
 */
const TONE_CLASSES: Record<BadgeTone, string> = {
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20',
  amber: 'bg-amber-50 text-amber-800 ring-amber-600/20',
  red: 'bg-red-50 text-red-700 ring-red-600/20',
  blue: 'bg-sky-50 text-sky-700 ring-sky-600/20',
  slate: 'bg-slate-100 text-slate-600 ring-slate-500/20',
};

@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span
    class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset whitespace-nowrap"
    [class]="classes()"
    ><ng-content
  /></span>`,
})
export class StatusBadge {
  readonly tone = input<BadgeTone>('slate');
  protected readonly classes = computed(() => TONE_CLASSES[this.tone()]);
}

import { Pipe, PipeTransform } from '@angular/core';

/**
 * Importo del contratto (stringa "50.00") -> "50,00 €".
 * Si usa Intl del browser: nessun dato di locale da registrare in Angular.
 */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(amount: string | number, currency = 'EUR'): string {
    const value = typeof amount === 'number' ? amount : Number(amount);
    if (Number.isNaN(value)) {
      return String(amount);
    }
    return new Intl.NumberFormat('it-IT', { style: 'currency', currency }).format(value);
  }
}

/** Data ISO del contratto (UTC) -> "25/11/2026, 14:17" nel fuso orario del browser. */
@Pipe({ name: 'dateTime' })
export class DateTimePipe implements PipeTransform {
  transform(iso: string | null | undefined): string {
    if (!iso) {
      return '';
    }
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return iso;
    }
    return new Intl.DateTimeFormat('it-IT', { dateStyle: 'short', timeStyle: 'short' }).format(date);
  }
}

/** Byte -> "1,3 MB". */
@Pipe({ name: 'fileSize' })
export class FileSizePipe implements PipeTransform {
  transform(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    const units = ['KB', 'MB', 'GB'];
    let value = bytes / 1024;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return `${new Intl.NumberFormat('it-IT', { maximumFractionDigits: 1 }).format(value)} ${units[unit]}`;
  }
}

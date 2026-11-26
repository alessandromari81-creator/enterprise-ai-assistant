import { DateTimePipe, FileSizePipe, MoneyPipe } from './format.pipes';

describe('MoneyPipe', () => {
  const pipe = new MoneyPipe();

  it('formatta la stringa decimale del contratto in euro con virgola', () => {
    expect(pipe.transform('50.00', 'EUR')).toMatch(/^50,00\s€$/);
    // In italiano il separatore delle migliaia compare dalle 5 cifre in su (regola CLDR di Intl).
    expect(pipe.transform('1234.5')).toMatch(/^1234,50\s€$/);
    expect(pipe.transform('12345.5')).toMatch(/^12\.345,50\s€$/);
  });

  it('non rompe con un valore non numerico', () => {
    expect(pipe.transform('abc')).toBe('abc');
  });
});

describe('DateTimePipe', () => {
  const pipe = new DateTimePipe();

  it('formatta una data ISO', () => {
    expect(pipe.transform('2026-11-25T14:17:11Z')).toMatch(/2026|26/);
  });

  it('gestisce vuoto e valori non validi', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform('non-una-data')).toBe('non-una-data');
  });
});

describe('FileSizePipe', () => {
  const pipe = new FileSizePipe();

  it('converte i byte', () => {
    expect(pipe.transform(512)).toBe('512 B');
    expect(pipe.transform(1536)).toBe('1,5 KB');
    expect(pipe.transform(5 * 1024 * 1024)).toBe('5 MB');
  });
});

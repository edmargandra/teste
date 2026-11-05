import { describe, expect, it } from 'vitest';
import { environment } from '../environments/environment';

describe('environment servido', () => {
  it('abre com o uuid do caixa sem atribuir o campo', () => {
    expect(environment.comercianteId).toBe('11111111-1111-4111-8111-111111111111');
  });
});

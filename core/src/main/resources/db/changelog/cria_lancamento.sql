CREATE TABLE lancamento (
    id UUID PRIMARY KEY,
    comerciante_id UUID NOT NULL,
    data DATE NOT NULL,
    valor NUMERIC(19, 2) NOT NULL,
    direcao TEXT NOT NULL,
    tipo_pagamento TEXT NOT NULL,
    descricao TEXT NOT NULL,
    comprovante BYTEA,
    CONSTRAINT lancamento_direcao_ck CHECK (direcao IN ('debito', 'credito')),
    CONSTRAINT lancamento_tipo_pagamento_ck CHECK (tipo_pagamento IN (
        'Pix',
        'cartão de crédito',
        'cartão de débito',
        'dinheiro',
        'boleto',
        'depósito'
    ))
);

CREATE INDEX lancamento_comerciante_data_idx ON lancamento (comerciante_id, data);

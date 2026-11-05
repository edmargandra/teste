import { ChangeDetectionStrategy, ChangeDetectorRef, Component, ElementRef, HostListener, OnInit, ViewChild, inject, signal } from '@angular/core';
import { environment } from '../environments/environment';
import {
  DiaSaldo,
  Linha,
  TAMANHO_PAGINA,
  avaliarPeriodo,
  limitesDoMes,
  campoDaRecusa,
  centavosVisiveis,
  classeDirecao,
  detalheInvalidaComerciante,
  diaDoCorpo,
  fraseAceite,
  fraseComprovante,
  fraseConsultaRecusada,
  fraseDaLista,
  fraseFalhaComprovante,
  fraseRegistroRecusado,
  idAceito,
  lerProblema,
  comercianteValido,
  mostrarQuantia,
  nomeDirecao,
  paginaDe,
  quantiaVisivel,
  reais,
  textoDeCentavos,
  textoLegivel,
  valorApi,
  falaValor,
} from './regras';

interface ComprovanteAberto {
  id: string;
  texto: string;
  frase: string;
}

@Component({
  selector: 'app-root',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
})
export class App implements OnInit {
  private readonly comercianteId: string;
  private readonly api = environment.apiBase.replace(/\/$/, '');
  private readonly cd = inject(ChangeDetectorRef);
  private editando: string | null = null;
  private arquivo: File | null = null;
  private listaPedido = 0;
  private saldoPedido = 0;
  private anunciado = '';

  @ViewChild('popup') private popup?: ElementRef<HTMLDialogElement>;

  readonly bloqueado = signal(false);
  readonly painel = signal<'lancamento' | 'consolidado'>('lancamento');
  readonly avisoLista = signal('');
  readonly avisoListaRecusa = signal(false);
  readonly avisoConsolidado = signal('');
  readonly avisoConsolidadoRecusa = signal(false);
  readonly avisoRegistro = signal('');
  readonly avisoRegistroRecusa = signal(false);
  readonly filtroData = signal('');
  readonly filtroTipo = signal('');
  readonly filtroDirecao = signal('');
  readonly linhas = signal<Linha[]>([]);
  readonly pagina = signal(0);
  readonly total = signal(0);
  readonly listaEmCurso = signal(false);
  readonly tituloPopup = signal('Incluir lançamento');
  readonly textoGravar = signal('Gravar lançamento');
  readonly gravando = signal(false);
  readonly data = signal('');
  readonly valor = signal('');
  readonly direcao = signal('');
  readonly tipo = signal('');
  readonly descricao = signal('');
  readonly manterComprovante = signal(false);
  readonly rotuloValor = signal<string | null>(null);
  readonly erros = signal<Record<string, string>>({});
  readonly invalidos = signal<ReadonlySet<string>>(new Set());
  private readonly mesAberto = limitesDoMes(new Date());
  readonly inicio = signal(this.mesAberto.inicio);
  readonly fim = signal(this.mesAberto.fim);
  readonly diasAcordeao = signal<DiaSaldo[]>([]);
  readonly temTotal = signal(false);
  readonly totalPeriodo = signal('');
  readonly comprovanteAberto = signal<ComprovanteAberto | null>(null);

  constructor() {
    const id = typeof environment.comercianteId === 'string' ? environment.comercianteId.trim() : '';
    this.comercianteId = id;
    if (!comercianteValido(id)) {
      this.bloqueado.set(true);
      this.avisoLista.set('Comerciante ausente.');
      this.avisoListaRecusa.set(true);
    }
  }

  ngOnInit(): void {
    if (!this.bloqueado()) void this.listar();
  }

  mostrarLancamento(): void {
    this.painel.set('lancamento');
  }

  mostrarConsolidado(): void {
    if (this.bloqueado()) {
      this.definirAvisoLista('Comerciante ausente.', true);
      return;
    }
    this.painel.set('consolidado');
  }

  anteriorDesabilitado(): boolean {
    return this.bloqueado() || this.listaEmCurso() || this.pagina() <= 0;
  }

  seguinteDesabilitado(): boolean {
    return this.bloqueado() || this.listaEmCurso() || (this.pagina() + 1) * TAMANHO_PAGINA >= this.total();
  }

  aoTexto(evento: Event, campo: { set(texto: string): void }): void {
    const alvo = evento.target;
    if (!(alvo instanceof HTMLInputElement) && !(alvo instanceof HTMLTextAreaElement)) return;
    const inicio = alvo.selectionStart;
    const fim = alvo.selectionEnd;
    campo.set(alvo.value);
    this.cd.detectChanges();
    if (document.activeElement === alvo && inicio !== null && fim !== null) alvo.setSelectionRange(inicio, fim);
  }

  valorDe(evento: Event): string {
    const alvo = evento.target;
    if (alvo instanceof HTMLInputElement || alvo instanceof HTMLTextAreaElement || alvo instanceof HTMLSelectElement) {
      return alvo.value;
    }
    return '';
  }

  invalido(id: string): string | null {
    return this.invalidos().has(id) ? 'true' : null;
  }

  mensagem(id: string): string | null {
    return this.invalidos().has(id) ? 'erro-' + id : null;
  }

  describedbyData(): string {
    return this.invalidos().has('data') ? 'data-descricao erro-data' : 'data-descricao';
  }

  quantiaLinha(linha: Linha): string {
    return quantiaVisivel(linha.valor);
  }

  quantiaTexto(texto: string): string {
    return mostrarQuantia(texto);
  }

  rotuloDirecao(direcao: string): string {
    return nomeDirecao(direcao);
  }

  classeDaDirecao(direcao: string): string {
    return classeDirecao(direcao);
  }

  filtrar(): void {
    if (this.bloqueado() || this.listaEmCurso()) return;
    const anterior = this.pagina();
    this.pagina.set(0);
    void this.listar(anterior);
  }

  paginaAnterior(): void {
    if (this.bloqueado() || this.listaEmCurso() || this.pagina() <= 0) return;
    const anterior = this.pagina();
    this.pagina.set(anterior - 1);
    void this.listar(anterior);
  }

  paginaSeguinte(): void {
    if (this.bloqueado() || this.listaEmCurso() || (this.pagina() + 1) * TAMANHO_PAGINA >= this.total()) return;
    const anterior = this.pagina();
    this.pagina.set(anterior + 1);
    void this.listar(anterior);
  }

  abrirInclusao(): void {
    if (this.bloqueado()) return;
    this.editando = null;
    this.limparFormulario();
    this.tituloPopup.set('Incluir lançamento');
    this.textoGravar.set('Registrar lançamento');
    this.dialogo()?.showModal();
    this.focar('data');
  }

  abrirAlteracao(linha: Linha): void {
    if (this.bloqueado()) return;
    this.editando = linha.id;
    this.limparFormulario();
    this.tituloPopup.set('Alterar lançamento');
    this.textoGravar.set('Alterar lançamento');
    this.data.set(linha.data || '');
    this.valor.set(typeof linha.valor === 'string' ? linha.valor : '');
    this.direcao.set(linha.direcao === 'debito' || linha.direcao === 'credito' ? linha.direcao : '');
    this.tipo.set(linha.tipo_pagamento || '');
    this.descricao.set(linha.descricao == null ? '' : String(linha.descricao));
    this.manterComprovante.set(true);
    this.dialogo()?.showModal();
    this.focar('data');
  }

  fecharPopup(): void {
    const popup = this.dialogo();
    if (popup?.open) {
      try {
        popup.close();
      } catch {
        popup.open = false;
      }
      if (popup.open) popup.open = false;
    }
    this.editando = null;
  }

  aoFecharPopup(): void {
    this.editando = null;
  }

  anunciarValor(): void {
    const fala = falaValor(this.valor());
    if (!fala) {
      this.rotuloValor.set(null);
      this.anunciado = '';
      return;
    }
    if (this.anunciado === fala) return;
    this.anunciado = fala;
    this.rotuloValor.set(fala);
  }

  aoArquivo(evento: Event): void {
    const alvo = evento.target;
    if (!(alvo instanceof HTMLInputElement) || !alvo.files || alvo.files.length === 0) {
      this.arquivo = null;
      return;
    }
    this.arquivo = alvo.files[0] ?? null;
  }

  async gravar(): Promise<void> {
    if (this.bloqueado()) return;
    this.limparErros();
    if (!this.data()) {
      this.recusarRegistro('data', 'Data', 'Recusa de lançamento');
      return;
    }
    const incluindo = !this.editando;
    const editando = this.editando;
    const caminho = incluindo ? '/lancamentos' : '/lancamentos/' + encodeURIComponent(editando ?? '');
    const popup = this.dialogo();
    this.definirAvisoRegistro('Pedido em curso.', false);
    this.gravando.set(true);
    try {
      const resposta = await this.buscar(caminho, {
        method: incluindo ? 'POST' : 'PUT',
        body: this.corpoRegistro(),
      });
      if (this.bloqueado() || !popup?.open) return;
      if (resposta.status === 201 || resposta.status === 200) {
        const aceito = idAceito(await this.lerJson(resposta));
        if (this.bloqueado() || !popup.open) return;
        if (!aceito || (editando && aceito !== editando)) {
          this.recusarRegistro('valor', 'Valor', 'Recusa de lançamento');
          return;
        }
        const paginaAntes = this.pagina();
        this.fecharPopup();
        if (incluindo) this.pagina.set(0);
        const listaOk = await this.listar(incluindo ? paginaAntes : this.pagina());
        if (listaOk && !this.bloqueado()) this.definirAvisoLista(fraseAceite(incluindo, this.total()), false);
        return;
      }
      const problema = lerProblema(await this.lerJson(resposta));
      if (this.bloqueado() || !popup.open) return;
      if (detalheInvalidaComerciante(problema.detail)) {
        this.fecharPopup();
        this.mostrarAusente();
        return;
      }
      const campo = campoDaRecusa(problema.detail);
      this.recusarRegistro(campo[0], campo[1], problema.title);
    } catch {
      if (this.bloqueado() || !popup?.open) return;
      this.definirAvisoRegistro('Registro recusado.', true);
    } finally {
      this.gravando.set(this.bloqueado());
    }
  }

  async abrirComprovante(linha: Linha): Promise<void> {
    if (this.bloqueado()) return;
    try {
      const resposta = await this.buscar('/lancamentos/' + encodeURIComponent(linha.id) + '/comprovante');
      if (this.bloqueado()) return;
      if (!resposta.ok) {
        this.falhaComprovante(linha);
        return;
      }
      const texto = textoLegivel(await resposta.arrayBuffer());
      if (this.bloqueado()) return;
      this.mostrarComprovante(linha, texto);
    } catch {
      if (this.bloqueado()) return;
      this.falhaComprovante(linha);
    }
  }

  fecharComprovante(id: string): void {
    if (this.comprovanteAberto()?.id === id) this.comprovanteAberto.set(null);
    this.focar('abrir-' + id);
  }

  async verSaldo(): Promise<void> {
    const pedido = ++this.saldoPedido;
    if (this.bloqueado()) {
      this.limparAcordeao();
      this.definirAvisoConsolidado('Comerciante ausente.', true);
      return;
    }
    const inicio = this.inicio();
    const fim = this.fim();
    this.limparAcordeao();
    if (inicio === '' && fim !== '') {
      this.definirAvisoConsolidado('A data inicial é obrigatória.', true);
      return;
    }
    if (fim === '') {
      this.definirAvisoConsolidado('A data final é obrigatória.', true);
      return;
    }
    const periodo = avaliarPeriodo(inicio, fim);
    if (periodo.tipo === 'incompleto') {
      this.definirAvisoConsolidado('A data é inválida.', true);
      return;
    }
    if (periodo.tipo === 'recusa') {
      this.definirAvisoConsolidado(periodo.frase, true);
      return;
    }
    if (pedido !== this.saldoPedido) return;
    this.definirAvisoConsolidado('', false);
    const recebidos: DiaSaldo[] = [];
    for (const data of periodo.datas) {
      const obtido = await this.pedirDia(data);
      if (pedido !== this.saldoPedido) return;
      if (this.bloqueado()) {
        this.limparAcordeao();
        this.definirAvisoConsolidado('Comerciante ausente.', true);
        return;
      }
      if ('erro' in obtido) {
        this.limparAcordeao();
        const frase = obtido.erro === 'incompleto'
          ? 'Saldo não servido.'
          : 'Consolidado diário indisponível. O saldo diário consolidado não é servido.';
        this.definirAvisoConsolidado(frase, true);
        return;
      }
      recebidos.push(obtido.dia);
    }
    if (pedido !== this.saldoPedido) return;
    this.pintarAcordeao(recebidos);
  }

  @HostListener('document:keydown', ['$event'])
  aoEsc(evento: KeyboardEvent): void {
    if (evento.key !== 'Escape') return;
    if (this.dialogo()?.open) return;
    const aberto = this.comprovanteAberto();
    if (!aberto) return;
    evento.preventDefault();
    this.fecharComprovante(aberto.id);
  }

  private async listar(paginaAnterior?: number): Promise<boolean> {
    if (this.bloqueado()) return false;
    const pedido = ++this.listaPedido;
    this.listaEmCurso.set(true);
    this.definirAvisoLista('Pedido em curso.', false);
    const params = new URLSearchParams();
    if (this.filtroData()) params.set('data', this.filtroData());
    if (this.filtroTipo()) params.set('tipo_pagamento', this.filtroTipo());
    if (this.filtroDirecao()) params.set('direcao', this.filtroDirecao());
    params.set('page', String(this.pagina()));
    params.set('size', String(TAMANHO_PAGINA));
    try {
      const resposta = await this.buscar('/lancamentos?' + params.toString());
      if (pedido !== this.listaPedido || this.bloqueado()) return false;
      if (!resposta.ok) {
        const problema = lerProblema(await this.lerJson(resposta));
        if (pedido !== this.listaPedido || this.bloqueado()) return false;
        if (detalheInvalidaComerciante(problema.detail)) {
          this.mostrarAusente();
          return false;
        }
        this.falhaDaLista(pedido, paginaAnterior, fraseConsultaRecusada(problema.title));
        return false;
      }
      const pagina = paginaDe(await this.lerJson(resposta));
      if (pedido !== this.listaPedido || this.bloqueado()) return false;
      if (!pagina) {
        this.falhaDaLista(pedido, paginaAnterior, 'Consulta recusada.');
        return false;
      }
      this.total.set(pagina.total);
      this.linhas.set(pagina.linhas);
      this.comprovanteAberto.set(null);
      this.definirAvisoLista(fraseDaLista(pagina.quantidade, pagina.total), false);
      return true;
    } catch {
      if (pedido !== this.listaPedido || this.bloqueado()) return false;
      this.falhaDaLista(pedido, paginaAnterior, 'Consulta recusada.');
      return false;
    } finally {
      if (pedido === this.listaPedido) this.listaEmCurso.set(false);
    }
  }

  private async pedirDia(data: string): Promise<{ erro: 'falha' | 'incompleto' } | { dia: DiaSaldo }> {
    try {
      const resposta = await this.buscar('/saldo-diario-consolidado?data=' + encodeURIComponent(data));
      if (!resposta.ok) return { erro: 'falha' };
      try {
        const dia = diaDoCorpo(data, await resposta.json());
        if (!dia) return { erro: 'incompleto' };
        return { dia };
      } catch {
        return { erro: 'incompleto' };
      }
    } catch {
      return { erro: 'falha' };
    }
  }

  private pintarAcordeao(dias: DiaSaldo[]): void {
    const visiveis = dias.filter((dia) => dia.grupos.length > 0);
    this.diasAcordeao.set(visiveis);
    this.totalPeriodo.set(reais(textoDeCentavos(centavosVisiveis(dias))));
    this.temTotal.set(true);
    this.definirAvisoConsolidado('', false);
  }

  private limparAcordeao(): void {
    this.diasAcordeao.set([]);
    this.temTotal.set(false);
    this.totalPeriodo.set('');
  }

  private corpoRegistro(): FormData {
    const dados = new FormData();
    dados.append('data', this.data());
    dados.append('valor', valorApi(this.valor()));
    dados.append('direcao', this.direcao());
    dados.append('tipo_pagamento', this.tipo());
    dados.append('descricao', this.descricao());
    const arquivo = this.arquivo;
    if (arquivo && arquivo.size > 0) dados.append('comprovante', arquivo, arquivo.name);
    return dados;
  }

  private async lerJson(resposta: Response): Promise<unknown> {
    try {
      return await resposta.json();
    } catch {
      return null;
    }
  }

  private recusarRegistro(idCampo: string, nome: string, titulo: string): void {
    const frase = fraseRegistroRecusado(nome, titulo);
    this.definirAvisoRegistro(frase, true);
    const ids = idCampo === 'debito' ? ['debito', 'credito'] : [idCampo];
    this.invalidos.set(new Set(ids));
    this.erros.set({ [idCampo]: frase });
    this.focar(idCampo);
  }

  private limparErros(): void {
    this.invalidos.set(new Set());
    this.erros.set({});
  }

  private limparFormulario(): void {
    this.limparErros();
    this.data.set('');
    this.valor.set('');
    this.direcao.set('');
    this.tipo.set('');
    this.descricao.set('');
    this.arquivo = null;
    this.rotuloValor.set(null);
    this.anunciado = '';
    const campo = document.getElementById('comprovante');
    if (campo instanceof HTMLInputElement) campo.value = '';
    this.manterComprovante.set(false);
    this.definirAvisoRegistro('', false);
  }

  private falhaDaLista(pedido: number, paginaAnterior: number | undefined, frase: string): void {
    if (pedido !== this.listaPedido || this.bloqueado()) return;
    if (typeof paginaAnterior === 'number') this.pagina.set(paginaAnterior);
    this.linhas.set([]);
    this.comprovanteAberto.set(null);
    this.definirAvisoLista(frase, true);
  }

  private mostrarComprovante(linha: Linha, texto: string): void {
    this.comprovanteAberto.set({
      id: linha.id,
      texto,
      frase: fraseComprovante(linha, texto),
    });
    this.definirAvisoLista(fraseComprovante(linha, texto), false);
    this.focar('fechar-' + linha.id);
  }

  private falhaComprovante(linha: Linha): void {
    this.definirAvisoLista(fraseFalhaComprovante(linha), false);
    if (this.comprovanteAberto()?.id === linha.id) this.comprovanteAberto.set(null);
    this.focar('abrir-' + linha.id);
  }

  private mostrarAusente(): void {
    this.bloqueado.set(true);
    this.definirAvisoLista('Comerciante ausente.', true);
    this.linhas.set([]);
    this.comprovanteAberto.set(null);
    this.total.set(0);
  }

  private definirAvisoLista(frase: string, recusa: boolean): void {
    this.avisoLista.set(frase);
    this.avisoListaRecusa.set(recusa);
  }

  private definirAvisoConsolidado(frase: string, recusa: boolean): void {
    this.avisoConsolidado.set(frase);
    this.avisoConsolidadoRecusa.set(recusa);
  }

  private definirAvisoRegistro(frase: string, recusa: boolean): void {
    this.avisoRegistro.set(frase);
    this.avisoRegistroRecusa.set(recusa);
  }

  private buscar(caminho: string, init: RequestInit = {}): Promise<Response> {
    return fetch(this.api + caminho, {
      ...init,
      headers: { 'X-Comerciante-Id': this.comercianteId },
      cache: 'no-store',
    });
  }

  private dialogo(): HTMLDialogElement | null {
    return this.popup?.nativeElement ?? null;
  }

  private focar(id: string): void {
    this.cd.detectChanges();
    document.getElementById(id)?.focus();
  }
}

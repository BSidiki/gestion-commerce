import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Api, CreditReport, PeriodReport, StockReport, errorMessage } from '../../core/api';
import { day, fcfa, quantity } from '../../core/format';

type Preset = 'jour' | '7j' | '30j' | 'mois' | 'mois-dernier' | 'libre';

interface Bar {
  label: string;
  revenue: number;
  margin: number;
  sales: number;
  /** Hauteur de la barre, en pourcentage de la plus haute. */
  height: number;
}

const PRESETS: { id: Preset; label: string }[] = [
  { id: 'jour', label: "Aujourd'hui" },
  { id: '7j', label: '7 jours' },
  { id: '30j', label: '30 jours' },
  { id: 'mois', label: 'Ce mois' },
  { id: 'mois-dernier', label: 'Mois dernier' },
  { id: 'libre', label: 'Autre période' },
];

/** Au-delà de deux mois, le graphique regroupe les journées par mois pour rester lisible. */
const MAX_DAILY_BARS = 62;
const MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];

function iso(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${String(date.getDate()).padStart(2, '0')}`;
}

function addDays(date: Date, days: number): Date {
  const copy = new Date(date);
  copy.setDate(copy.getDate() + days);
  return copy;
}

function parse(isoDate: string): Date {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Date(y, m - 1, d);
}

/** Tableau de bord : activité d'une période, état du stock, crédits en attente. */
@Component({
  selector: 'app-dashboard',
  imports: [FormsModule, RouterLink],
  styles: `
    .period {
      display: flex;
      flex-wrap: wrap;
      align-items: end;
      gap: 0.75rem 1rem;
      margin-bottom: 1.25rem;
    }
    .period .dates {
      display: flex;
      gap: 0.5rem;
      align-items: end;
    }
    .period .dates label {
      font-size: 0.85rem;
    }
    .stats.wide {
      grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
    }
    .columns {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(22rem, 1fr));
      gap: 1.1rem;
      align-items: start;
    }
    .columns .card {
      margin-bottom: 0;
    }
    section.block {
      margin-top: 2rem;
    }
    .block-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      flex-wrap: wrap;
      margin-bottom: 0.75rem;
    }
    .block-head h2 {
      margin: 0;
      font-size: 1.35rem;
    }
    .block-head select {
      width: auto;
    }

    /* Graphique : une seule série, donc une seule teinte et pas de légende. */
    .chart {
      --bar: var(--accent);
      display: grid;
      grid-template-columns: auto minmax(0, 1fr);
      gap: 0 0.6rem;
    }
    :host-context([data-theme='dark']) .chart {
      --bar: var(--accent-text);
    }
    .axis {
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      height: 13rem;
      font-size: 0.78rem;
      color: var(--muted);
      text-align: right;
      font-variant-numeric: tabular-nums;
    }
    .plot {
      position: relative;
      display: flex;
      align-items: flex-end;
      gap: 2px;
      height: 13rem;
      border-bottom: 1px solid var(--line-strong);
      background: linear-gradient(var(--line), var(--line)) 0 0 / 100% 1px no-repeat,
        linear-gradient(var(--line), var(--line)) 0 50% / 100% 1px no-repeat;
    }
    .slot {
      position: relative;
      flex: 1 1 0;
      min-width: 0;
      height: 100%;
      display: flex;
      align-items: flex-end;
      justify-content: center;
      border: none;
      padding: 0;
      background: transparent;
      font: inherit;
      cursor: default;
    }
    .slot .bar {
      width: 100%;
      max-width: 2.2rem;
      min-height: 0;
      border-radius: 4px 4px 0 0;
      background: var(--bar);
    }
    .slot:hover,
    .slot:focus-visible {
      background: var(--accent-soft);
      outline: none;
    }
    .slot .tip {
      display: none;
      position: absolute;
      bottom: calc(100% - 3rem);
      left: 50%;
      transform: translateX(-50%);
      z-index: 5;
      width: max-content;
      padding: 0.5rem 0.7rem;
      border-radius: 8px;
      border: 1px solid var(--line-strong);
      background: var(--surface);
      color: var(--ink);
      font-size: 0.82rem;
      text-align: left;
      line-height: 1.4;
      pointer-events: none;
    }
    .slot:first-child .tip {
      left: 0;
      transform: none;
    }
    .slot:last-child .tip {
      left: auto;
      right: 0;
      transform: none;
    }
    .slot:hover .tip,
    .slot:focus-visible .tip {
      display: block;
    }
    .tip strong {
      display: block;
    }
    .ticks {
      grid-column: 2;
      display: flex;
      justify-content: space-between;
      margin-top: 0.35rem;
      font-size: 0.78rem;
      color: var(--muted);
    }

    /* Classement : la longueur de la barre redit le montant, le chiffre reste en clair. */
    .rank {
      display: flex;
      flex-direction: column;
      gap: 0.7rem;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .rank .row {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      align-items: baseline;
    }
    .rank .amount {
      font-variant-numeric: tabular-nums;
      font-weight: 600;
      white-space: nowrap;
    }
    .rank .track {
      height: 0.4rem;
      margin-top: 0.3rem;
      border-radius: 4px;
      background: var(--surface-2);
    }
    .rank .fill {
      height: 100%;
      border-radius: 4px;
      background: var(--accent);
    }
    :host-context([data-theme='dark']) .rank .fill {
      background: var(--accent-text);
    }
  `,
  template: `
    <div class="page-head">
      <h1>Tableau de bord</h1>
      @if (report(); as r) {
        <button type="button" class="button" (click)="exportDays(r)" [disabled]="r.days.length === 0">
          Exporter pour Excel
        </button>
      }
    </div>

    <div class="period">
      <div class="choices" role="radiogroup" aria-label="Période">
        @for (option of presets; track option.id) {
          <label class="choice">
            <input
              type="radio"
              name="preset"
              [value]="option.id"
              [checked]="preset() === option.id"
              (change)="choose(option.id)"
            />
            {{ option.label }}
          </label>
        }
      </div>
      @if (preset() === 'libre') {
        <div class="dates">
          <label>
            Du
            <input type="date" name="from" [(ngModel)]="from" [max]="to" />
          </label>
          <label>
            au
            <input type="date" name="to" [(ngModel)]="to" [min]="from" />
          </label>
          <button type="button" class="button primary" (click)="load()">Afficher</button>
        </div>
      }
    </div>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }

    @if (report(); as r) {
      <p class="muted">{{ periodLabel(r) }}</p>
      <div class="stats wide">
        <div class="stat">
          <span class="label">Chiffre d'affaires</span>
          <span class="value">{{ money(r.revenue) }}</span>
          <span class="muted">{{ r.saleCount }} vente(s)</span>
        </div>
        <div class="stat">
          <span class="label">Marge brute</span>
          <span class="value">{{ money(r.grossMargin) }}</span>
          <span class="muted">{{ rate(r) }}</span>
        </div>
        <div class="stat">
          <span class="label">Dépenses</span>
          <span class="value">{{ money(r.expenses) }}</span>
        </div>
        <div class="stat">
          <span class="label">Résultat estimé</span>
          <span class="value" [class.negative]="r.estimatedResult < 0">{{ money(r.estimatedResult) }}</span>
          <span class="muted">marge brute moins dépenses</span>
        </div>
        <div class="stat">
          <span class="label">Encaissé</span>
          <span class="value">{{ money(r.cash + r.mobileMoney) }}</span>
          <span class="muted">espèces {{ money(r.cash) }}, Mobile Money {{ money(r.mobileMoney) }}</span>
        </div>
        <div class="stat">
          <span class="label">Vendu à crédit</span>
          <span class="value">{{ money(r.creditGranted) }}</span>
          <span class="muted">remboursements reçus : {{ money(r.repaymentsReceived) }}</span>
        </div>
      </div>

      @if (r.saleCount === 0 && r.expenses === 0) {
        <div class="empty">
          <p>Aucune vente ni dépense sur cette période.</p>
        </div>
      } @else {
        <section class="card">
          <h2>Chiffre d'affaires {{ monthly() ? 'par mois' : 'par jour' }}</h2>
          <div class="chart">
            <div class="axis" aria-hidden="true">
              <span>{{ money(top()) }}</span>
              <span>{{ money(top() / 2) }}</span>
              <span>0</span>
            </div>
            <div class="plot" role="list" aria-label="Chiffre d'affaires de la période">
              @for (bar of bars(); track bar.label) {
                <button type="button" class="slot" role="listitem">
                  <span class="bar" [style.height.%]="bar.height"></span>
                  <span class="tip">
                    <strong>{{ bar.label }}</strong>
                    {{ money(bar.revenue) }} · {{ bar.sales }} vente(s)<br />
                    marge {{ money(bar.margin) }}
                  </span>
                </button>
              }
            </div>
            <div class="ticks" aria-hidden="true">
              @for (tick of ticks(); track $index) {
                <span>{{ tick }}</span>
              }
            </div>
          </div>
        </section>

        <div class="columns">
          <section class="card">
            <h2>Produits les plus vendus</h2>
            @if (r.topProducts.length) {
              <ol class="rank">
                @for (p of r.topProducts; track p.productId) {
                  <li>
                    <div class="row">
                      <span>
                        <a [routerLink]="['/produits', p.productId]">{{ p.name }}</a>
                        <span class="muted small"> · {{ qty(p.quantity) }} {{ p.baseUnit }}</span>
                      </span>
                      <span class="amount">{{ money(p.revenue) }}</span>
                    </div>
                    <div class="track"><div class="fill" [style.width.%]="share(p.revenue, r.topProducts[0].revenue)"></div></div>
                    <div class="muted small">marge {{ money(p.grossMargin) }}</div>
                  </li>
                }
              </ol>
            } @else {
              <p class="muted">Aucune vente sur cette période.</p>
            }
          </section>

          <section class="card">
            <h2>Dépenses par catégorie</h2>
            @if (r.expensesByCategory.length) {
              <ol class="rank">
                @for (c of r.expensesByCategory; track c.category) {
                  <li>
                    <div class="row">
                      <span>{{ c.category }}</span>
                      <span class="amount">{{ money(c.amount) }}</span>
                    </div>
                    <div class="track"><div class="fill" [style.width.%]="share(c.amount, r.expensesByCategory[0].amount)"></div></div>
                  </li>
                }
              </ol>
            } @else {
              <p class="muted">Aucune dépense sur cette période.</p>
            }

            @if (r.sellers.length > 1) {
              <h2 style="margin-top: 1.5rem">Ventes par personne</h2>
              <table class="table compact">
                <tbody>
                  @for (s of r.sellers; track $index) {
                    <tr>
                      <td>{{ s.displayName || s.username || 'Avant la création des comptes' }}</td>
                      <td class="num">{{ s.saleCount }} vente(s)</td>
                      <td class="num">{{ money(s.revenue) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </section>
        </div>
      }
    } @else if (!error()) {
      <p class="muted">Chargement…</p>
    }

    @if (stock(); as s) {
      <section class="block">
        <div class="block-head">
          <h2>Stock</h2>
          <label class="check">
            Sans vente depuis
            <select name="dormantDays" [ngModel]="dormantDays()" (ngModelChange)="setDormantDays($event)">
              <option [ngValue]="30">30 jours</option>
              <option [ngValue]="60">60 jours</option>
              <option [ngValue]="90">90 jours</option>
              <option [ngValue]="180">6 mois</option>
            </select>
          </label>
        </div>
        <div class="stats">
          <div class="stat">
            <span class="label">Valeur du stock</span>
            <span class="value">{{ money(s.stockValue) }}</span>
            <span class="muted">{{ s.productCount }} produit(s), au coût d'achat moyen</span>
          </div>
          <div class="stat">
            <span class="label">Sous le seuil d'alerte</span>
            <span class="value" [class.negative]="s.lowStockCount > 0">{{ s.lowStockCount }}</span>
            <a routerLink="/produits">Voir les produits</a>
          </div>
          <div class="stat">
            <span class="label">Stock qui dort</span>
            <span class="value">{{ money(s.dormantValue) }}</span>
            <span class="muted">{{ s.dormantCount }} produit(s) sans vente depuis {{ s.dormantDays }} jours</span>
          </div>
        </div>
        @if (s.dormant.length) {
          <table class="table">
            <thead>
              <tr>
                <th>Produit en stock, sans vente récente</th>
                <th class="num">Stock</th>
                <th class="num">Valeur</th>
                <th>Dernière vente</th>
              </tr>
            </thead>
            <tbody>
              @for (p of s.dormant; track p.productId) {
                <tr>
                  <td><a [routerLink]="['/produits', p.productId]">{{ p.name }}</a></td>
                  <td class="num">{{ qty(p.stockQty) }} {{ p.baseUnit }}</td>
                  <td class="num">{{ money(p.value) }}</td>
                  <td>{{ p.lastSoldOn ? date(p.lastSoldOn) : 'Jamais vendu' }}</td>
                </tr>
              }
            </tbody>
          </table>
          @if (s.dormantCount > s.dormant.length) {
            <p class="muted small">Les {{ s.dormant.length }} plus gros montants sont affichés.</p>
          }
        }
      </section>
    }

    @if (credits(); as c) {
      <section class="block">
        <div class="block-head">
          <h2>Crédits clients</h2>
        </div>
        <div class="stats">
          <div class="stat">
            <span class="label">Reste à encaisser</span>
            <span class="value">{{ money(c.totalOutstanding) }}</span>
            <span class="muted">{{ c.customerCount }} client(s)</span>
          </div>
        </div>
        @if (c.customers.length) {
          <table class="table">
            <thead>
              <tr>
                <th>Client</th>
                <th>Téléphone</th>
                <th class="num">Doit</th>
                <th>Sans paiement depuis</th>
              </tr>
            </thead>
            <tbody>
              @for (line of c.customers; track line.customerId) {
                <tr>
                  <td><a [routerLink]="['/clients', line.customerId]">{{ line.name }}</a></td>
                  <td>{{ line.phone || '—' }}</td>
                  <td class="num">{{ money(line.balance) }}</td>
                  <td>
                    {{ waiting(line.daysWaiting) }}
                    @if (line.daysWaiting >= 30) {
                      <span class="badge warning">À relancer</span>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>
    }
  `,
})
export class Dashboard {
  private readonly api = inject(Api);

  protected readonly presets = PRESETS;
  protected readonly preset = signal<Preset>('30j');
  protected readonly report = signal<PeriodReport | null>(null);
  protected readonly stock = signal<StockReport | null>(null);
  protected readonly credits = signal<CreditReport | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly dormantDays = signal(30);

  protected from = iso(addDays(new Date(), -29));
  protected to = iso(new Date());

  protected readonly money = fcfa;
  protected readonly qty = quantity;
  protected readonly date = day;

  protected readonly monthly = computed(() => {
    const r = this.report();
    return r !== null && this.span(r) > MAX_DAILY_BARS;
  });

  /** Une barre par jour de la période (jours sans vente compris), ou par mois si elle est longue. */
  protected readonly bars = computed<Bar[]>(() => {
    const r = this.report();
    if (!r) {
      return [];
    }
    const byDate = new Map(r.days.map(d => [d.date, d]));
    const buckets = new Map<string, Bar>();
    const end = parse(r.to);
    for (let d = parse(r.from); d <= end; d = addDays(d, 1)) {
      const key = iso(d);
      const label = this.monthly() ? `${MONTHS[d.getMonth()]} ${d.getFullYear()}` : day(key);
      const bar = buckets.get(label) ?? { label, revenue: 0, margin: 0, sales: 0, height: 0 };
      const line = byDate.get(key);
      if (line) {
        bar.revenue += line.revenue;
        bar.margin += line.grossMargin;
        bar.sales += line.saleCount;
      }
      buckets.set(label, bar);
    }
    const bars = [...buckets.values()];
    const top = this.niceTop(Math.max(0, ...bars.map(b => b.revenue)));
    return bars.map(b => ({ ...b, height: top ? (b.revenue / top) * 100 : 0 }));
  });

  /** Haut de l'axe : la plus grande barre, arrondie à une valeur ronde au-dessus. */
  protected readonly top = computed(() => {
    const r = this.report();
    if (!r) {
      return 0;
    }
    const height = this.bars().find(b => b.height > 0);
    return height ? Math.round((height.revenue * 100) / height.height) : 0;
  });

  /** Repères de l'axe du temps : le début, le milieu et la fin, pas chaque barre. */
  protected readonly ticks = computed(() => {
    const bars = this.bars();
    if (bars.length <= 2) {
      return bars.map(b => b.label);
    }
    return [bars[0].label, bars[Math.floor(bars.length / 2)].label, bars[bars.length - 1].label];
  });

  constructor() {
    this.choose('30j');
    this.loadStock();
    this.api.creditReport().subscribe({
      next: credits => this.credits.set(credits),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected choose(preset: Preset): void {
    this.preset.set(preset);
    const today = new Date();
    switch (preset) {
      case 'jour':
        this.from = this.to = iso(today);
        break;
      case '7j':
        this.from = iso(addDays(today, -6));
        this.to = iso(today);
        break;
      case '30j':
        this.from = iso(addDays(today, -29));
        this.to = iso(today);
        break;
      case 'mois':
        this.from = iso(new Date(today.getFullYear(), today.getMonth(), 1));
        this.to = iso(today);
        break;
      case 'mois-dernier':
        this.from = iso(new Date(today.getFullYear(), today.getMonth() - 1, 1));
        this.to = iso(new Date(today.getFullYear(), today.getMonth(), 0));
        break;
      case 'libre':
        // Les dates se choisissent dans les deux champs, puis « Afficher ».
        return;
    }
    this.load();
  }

  protected load(): void {
    if (!this.from || !this.to) {
      this.error.set('Choisissez une date de début et une date de fin.');
      return;
    }
    this.error.set(null);
    this.api.periodReport(this.from, this.to).subscribe({
      next: report => this.report.set(report),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  protected setDormantDays(days: number): void {
    this.dormantDays.set(days);
    this.loadStock();
  }

  protected periodLabel(r: PeriodReport): string {
    return r.from === r.to ? `Le ${day(r.from)}` : `Du ${day(r.from)} au ${day(r.to)}`;
  }

  protected rate(r: PeriodReport): string {
    return r.revenue > 0 ? `${Math.round((r.grossMargin * 100) / r.revenue)} % du chiffre d'affaires` : '';
  }

  protected share(amount: number, largest: number): number {
    return largest > 0 ? Math.max(2, (amount / largest) * 100) : 0;
  }

  protected waiting(days: number): string {
    return days <= 0 ? "aujourd'hui" : days === 1 ? '1 jour' : `${days} jours`;
  }

  /** Les journées de la période dans un fichier qu'Excel ouvre directement. */
  protected exportDays(r: PeriodReport): void {
    const lines = [
      ['Date', 'Ventes', "Chiffre d'affaires", 'Coût des marchandises', 'Marge brute', 'Dépenses'],
      ...r.days.map(d => [d.date, d.saleCount, d.revenue, d.costOfGoods, d.grossMargin, d.expenses]),
      ['Total', r.saleCount, r.revenue, r.costOfGoods, r.grossMargin, r.expenses],
    ];
    const csv = '﻿' + lines.map(line => line.join(';')).join('\r\n') + '\r\n';
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `activite-${r.from}-au-${r.to}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  private loadStock(): void {
    this.api.stockReport(this.dormantDays()).subscribe({
      next: stock => this.stock.set(stock),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  private span(r: PeriodReport): number {
    return Math.round((parse(r.to).getTime() - parse(r.from).getTime()) / 86_400_000) + 1;
  }

  /** Arrondit vers le haut à 1, 2 ou 5 fois une puissance de dix : 3 600 devient 5 000. */
  private niceTop(value: number): number {
    if (value <= 0) {
      return 0;
    }
    const power = Math.pow(10, Math.floor(Math.log10(value)));
    const step = [1, 2, 5, 10].find(s => s * power >= value) ?? 10;
    return step * power;
  }
}

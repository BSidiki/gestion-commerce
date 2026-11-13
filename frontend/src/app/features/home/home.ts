import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Api, CashStatus, HomeSettings, PeriodReport, Sale, errorMessage } from '../../core/api';
import { Appearance } from '../../core/appearance';
import { AuthState } from '../../core/auth-state';
import { fcfa } from '../../core/format';
import { Icon } from '../../core/icon';
import { LicenseState } from '../../core/license-state';

interface Shortcut {
  id: string;
  label: string;
  hint: string;
  icon: string;
  link: string;
  /** Réservé au propriétaire et à la secrétaire. */
  manager?: boolean;
}

/** Tous les raccourcis possibles. Le propriétaire choisit lesquels s'affichent, dans la page Boutique. */
export const SHORTCUTS: Shortcut[] = [
  { id: 'vente', label: 'Nouvelle vente', hint: 'Ouvrir la caisse', icon: 'caisse', link: '/caisse' },
  { id: 'devis', label: 'Devis', hint: 'Préparer ou retrouver un devis', icon: 'devis', link: '/devis' },
  { id: 'depense', label: 'Dépense', hint: 'Noter une sortie d\'argent', icon: 'depenses', link: '/depenses' },
  { id: 'cloture', label: 'Caisse', hint: 'Ouvrir ou clôturer la caisse', icon: 'cloture', link: '/cloture' },
  { id: 'produit', label: 'Nouveau produit', hint: 'L\'ajouter au catalogue', icon: 'produits', link: '/produits/nouveau', manager: true },
  { id: 'achat', label: 'Achat fournisseur', hint: 'Recevoir une livraison', icon: 'fournisseurs', link: '/fournisseurs', manager: true },
  { id: 'inventaire', label: 'Inventaire', hint: 'Compter le stock', icon: 'inventaire', link: '/inventaire', manager: true },
  { id: 'clients', label: 'Clients', hint: 'Crédits et remboursements', icon: 'clients', link: '/clients' },
  { id: 'rapports', label: 'Tableau de bord', hint: 'Chiffres de la période', icon: 'rapports', link: '/tableau-de-bord', manager: true },
  { id: 'import', label: 'Importer des produits', hint: 'Depuis un tableau Excel', icon: 'produits', link: '/catalogue/importer', manager: true },
];

interface Alert {
  text: string;
  action: string;
  link: string;
  tone: 'warning' | 'neutral';
}

const WEEKDAYS = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi'];
const MONTHS = [
  'janvier', 'février', 'mars', 'avril', 'mai', 'juin',
  'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre',
];

function iso(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${String(date.getDate()).padStart(2, '0')}`;
}

/** La page d'accueil : la boutique, la journée en cours, les raccourcis et ce qu'il faut surveiller. */
@Component({
  selector: 'app-home',
  imports: [RouterLink, Icon],
  styles: `
    /* Le bandeau porte l'identité de la boutique : sa couleur, et sa photo si elle en a une. */
    .hero {
      position: relative;
      overflow: hidden;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 2rem;
      align-items: end;
      min-height: 13.5rem;
      padding: 2.25rem;
      border-radius: 22px;
      background: var(--sidebar);
      color: #fff;
      margin-bottom: 1.5rem;
    }
    .hero.photo {
      min-height: 17rem;
      background-size: cover;
      background-position: center;
    }
    .hero.photo::before {
      content: '';
      position: absolute;
      inset: 0;
      background: linear-gradient(100deg, color-mix(in srgb, var(--sidebar) 94%, transparent) 0%,
        color-mix(in srgb, var(--sidebar) 70%, transparent) 55%, color-mix(in srgb, var(--sidebar) 25%, transparent) 100%);
    }
    .hero > * {
      position: relative;
    }
    .shop {
      display: flex;
      align-items: center;
      gap: 0.7rem;
      margin: 0 0 1.5rem;
      font-weight: 600;
      color: rgba(255, 255, 255, 0.85);
    }
    .shop img {
      width: 2.75rem;
      height: 2.75rem;
      border-radius: 12px;
      background: #fff;
      object-fit: contain;
    }
    .hero h1 {
      font-size: clamp(2.2rem, 4.5vw, 3.4rem);
      font-weight: 700;
      letter-spacing: -0.03em;
      line-height: 1.02;
      margin: 0;
    }
    .hero .date {
      margin: 0.6rem 0 0;
      font-size: 1.05rem;
      color: rgba(255, 255, 255, 0.85);
    }
    .hero .message {
      margin: 1rem 0 0;
      max-width: 34rem;
      font-size: 1.15rem;
      line-height: 1.4;
    }
    .today {
      min-width: 15rem;
      padding: 1.25rem 1.4rem;
      border-radius: 16px;
      background: #fff;
      color: var(--sidebar);
    }
    .today .label {
      font-weight: 600;
    }
    .today .figure {
      display: block;
      margin: 0.15rem 0;
      font-family: var(--font-display);
      font-size: 2.4rem;
      font-weight: 700;
      letter-spacing: -0.03em;
      line-height: 1.05;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    .today .detail {
      color: color-mix(in srgb, var(--sidebar) 75%, #fff);
    }
    @media (max-width: 760px) {
      .hero {
        grid-template-columns: 1fr;
        padding: 1.5rem;
        min-height: 0;
      }
    }

    h2 {
      font-size: 1.35rem;
      margin: 0 0 0.8rem;
    }
    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr));
      gap: 0.9rem;
      margin-bottom: 2rem;
    }
    .tile {
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
      padding: 1.1rem 1.15rem;
      border: 1px solid var(--line);
      border-radius: 16px;
      background: var(--surface);
      color: var(--ink);
      text-decoration: none;
    }
    .tile:hover,
    .tile:focus-visible {
      border-color: var(--accent);
      background: var(--accent-soft);
      outline: none;
    }
    .tile:focus-visible {
      box-shadow: 0 0 0 3px var(--focus);
    }
    .tile .mark {
      display: grid;
      place-items: center;
      width: 2.6rem;
      height: 2.6rem;
      margin-bottom: 0.7rem;
      border-radius: 12px;
      background: var(--accent);
      color: #fff;
    }
    .tile .mark {
      --icon-size: 1.4rem;
    }
    .tile strong {
      font-family: var(--font-display);
      font-size: 1.12rem;
      font-weight: 650;
    }
    .tile span {
      color: var(--muted);
      font-size: 0.92rem;
    }
    /* La première tuile est le geste le plus fréquent : elle prend la couleur de la boutique. */
    .tile.main {
      background: var(--accent);
      border-color: var(--accent);
      color: #fff;
    }
    .tile.main:hover,
    .tile.main:focus-visible {
      background: var(--accent-strong);
    }
    .tile.main .mark {
      background: #fff;
      color: var(--accent);
    }
    .tile.main span {
      color: rgba(255, 255, 255, 0.85);
    }

    .lower {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(21rem, 1fr));
      gap: 1.1rem;
      align-items: start;
    }
    .lower .card {
      margin: 0;
    }
    .watch {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .watch li {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: 0.75rem 0;
      border-bottom: 1px solid var(--line);
    }
    .watch li:last-child {
      border-bottom: none;
      padding-bottom: 0;
    }
    .watch li:first-child {
      padding-top: 0;
    }
    .watch .dot {
      flex: none;
      width: 0.6rem;
      height: 0.6rem;
      border-radius: 50%;
      background: var(--line-strong);
    }
    .watch .warning .dot {
      background: #d98a0b;
    }
    .watch .text {
      flex: 1;
    }
    .watch a {
      white-space: nowrap;
      font-weight: 600;
    }

    .week {
      --bar: var(--accent);
      display: flex;
      align-items: flex-end;
      gap: 0.6rem;
      height: 9rem;
    }
    :host-context([data-theme='dark']) .week {
      --bar: var(--accent-text);
    }
    .week .day {
      flex: 1;
      height: 100%;
      display: flex;
      flex-direction: column;
      justify-content: flex-end;
      align-items: center;
      gap: 0.35rem;
      min-width: 0;
    }
    .week .bar {
      width: 100%;
      max-width: 3rem;
      border-radius: 4px 4px 0 0;
      background: var(--bar);
      opacity: 0.45;
    }
    .week .day.now .bar {
      opacity: 1;
    }
    .week .amount {
      font-size: 0.75rem;
      font-variant-numeric: tabular-nums;
      color: var(--muted);
      white-space: nowrap;
    }
    .week .name {
      font-size: 0.82rem;
      color: var(--muted);
    }
    .week .day.now .name {
      color: var(--ink);
      font-weight: 650;
    }
    .week-total {
      margin: 0.9rem 0 0;
    }
  `,
  template: `
    <section
      class="hero"
      [class.photo]="settings()?.cover"
      [style.background-image]="settings()?.cover ? 'url(' + settings()!.cover + ')' : null"
    >
      <div>
        <p class="shop">
          @if (look.logo(); as logo) {
            <img [src]="logo" alt="" />
          }
          {{ look.displayName() }}
        </p>
        <h1>{{ greeting() }}</h1>
        <p class="date">{{ today }}</p>
        @if (settings()?.welcomeMessage; as message) {
          <p class="message">{{ message }}</p>
        }
      </div>
      <div class="today">
        <span class="label">Ventes d'aujourd'hui</span>
        <span class="figure">{{ money(todayRevenue()) }}</span>
        <span class="detail">{{ todayDetail() }}</span>
      </div>
    </section>

    @if (error(); as message) {
      <p class="alert error" role="alert">{{ message }}</p>
    }

    @if (shortcuts().length) {
      <h2>Que voulez-vous faire ?</h2>
      <div class="tiles">
        @for (s of shortcuts(); track s.id; let first = $first) {
          <a class="tile" [class.main]="first" [routerLink]="s.link">
            <span class="mark"><app-icon [name]="s.icon" /></span>
            <strong>{{ s.label }}</strong>
            <span>{{ s.hint }}</span>
          </a>
        }
      </div>
    }

    <div class="lower">
      <section class="card">
        <h2>À surveiller</h2>
        @if (alerts().length) {
          <ul class="watch">
            @for (alert of alerts(); track alert.text) {
              <li [class.warning]="alert.tone === 'warning'">
                <span class="dot"></span>
                <span class="text">{{ alert.text }}</span>
                <a [routerLink]="alert.link">{{ alert.action }}</a>
              </li>
            }
          </ul>
        } @else {
          <p class="muted">Rien à signaler pour le moment.</p>
        }
      </section>

      @if (week(); as w) {
        <section class="card">
          <h2>Les 7 derniers jours</h2>
          <div class="week" role="list" aria-label="Chiffre d'affaires des sept derniers jours">
            @for (d of weekBars(); track d.date) {
              <div class="day" [class.now]="d.now" role="listitem">
                <span class="amount">{{ d.revenue ? short(d.revenue) : '' }}</span>
                <span class="bar" [style.height.%]="d.height"></span>
                <span class="name">{{ d.name }}</span>
              </div>
            }
          </div>
          <p class="week-total">
            <strong>{{ money(w.revenue) }}</strong> de ventes, <strong>{{ money(w.grossMargin) }}</strong> de marge
            brute. <a routerLink="/tableau-de-bord">Voir le tableau de bord</a>
          </p>
        </section>
      }
    </div>
  `,
})
export class Home {
  private readonly api = inject(Api);
  protected readonly auth = inject(AuthState);
  protected readonly look = inject(Appearance);
  private readonly license = inject(LicenseState);

  protected readonly settings = signal<HomeSettings | null>(null);
  protected readonly sales = signal<Sale[]>([]);
  protected readonly cash = signal<CashStatus | null>(null);
  protected readonly lowStock = signal(0);
  protected readonly openQuotes = signal(0);
  protected readonly creditsToChase = signal(0);
  protected readonly supplierDebt = signal(0);
  protected readonly week = signal<PeriodReport | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly money = fcfa;
  protected readonly today = this.longDate(new Date());

  protected readonly greeting = computed(() => {
    const name = this.auth.user()?.displayName?.split(' ')[0] ?? '';
    const hello = new Date().getHours() >= 18 ? 'Bonsoir' : 'Bonjour';
    return name ? `${hello} ${name}` : hello;
  });

  private readonly validSales = computed(() => this.sales().filter(s => s.status === 'VALIDEE'));
  protected readonly todayRevenue = computed(() => this.validSales().reduce((sum, s) => sum + s.total, 0));
  protected readonly todayDetail = computed(() => {
    const count = this.validSales().length;
    if (count === 0) {
      return 'Aucune vente pour le moment';
    }
    const credit = this.validSales().reduce((sum, s) => sum + s.credit, 0);
    const sales = count === 1 ? '1 vente' : `${count} ventes`;
    return credit > 0 ? `${sales}, dont ${fcfa(credit)} à crédit` : sales;
  });

  /** Les raccourcis choisis par le propriétaire, limités à ce que le rôle connecté peut ouvrir. */
  protected readonly shortcuts = computed(() => {
    const chosen = this.settings()?.shortcuts ?? [];
    return chosen
      .map(id => SHORTCUTS.find(s => s.id === id))
      .filter((s): s is Shortcut => !!s && (!s.manager || this.auth.isManager()));
  });

  protected readonly alerts = computed<Alert[]>(() => {
    const alerts: Alert[] = [];
    const license = this.license.status();
    if (license?.readOnly) {
      alerts.push({ text: 'La licence a expiré : plus rien ne s\'enregistre.', action: 'Voir la licence', link: '/licence', tone: 'warning' });
    } else if (license?.warning) {
      alerts.push({ text: `La licence expire dans ${license.daysLeft} jour(s).`, action: 'Renouveler', link: '/licence', tone: 'warning' });
    }
    const cash = this.cash();
    if (cash && !cash.open) {
      alerts.push({ text: 'La caisse n\'est pas ouverte.', action: 'Ouvrir la caisse', link: '/cloture', tone: 'warning' });
    }
    if (this.lowStock() > 0) {
      alerts.push({
        text: `${this.lowStock()} produit(s) sous le seuil d'alerte.`,
        action: 'Voir les produits',
        link: '/produits',
        tone: 'warning',
      });
    }
    if (this.creditsToChase() > 0) {
      alerts.push({
        text: `${this.creditsToChase()} client(s) n'ont rien payé depuis plus de 30 jours.`,
        action: 'Voir les crédits',
        link: '/tableau-de-bord',
        tone: 'warning',
      });
    }
    if (this.supplierDebt() > 0) {
      alerts.push({
        text: `${fcfa(this.supplierDebt())} à payer aux fournisseurs.`,
        action: 'Voir les fournisseurs',
        link: '/fournisseurs',
        tone: 'neutral',
      });
    }
    if (this.openQuotes() > 0) {
      alerts.push({ text: `${this.openQuotes()} devis en attente de réponse.`, action: 'Voir les devis', link: '/devis', tone: 'neutral' });
    }
    return alerts;
  });

  /** Une barre par jour, du plus ancien à aujourd'hui, jours sans vente compris. */
  protected readonly weekBars = computed(() => {
    const report = this.week();
    if (!report) {
      return [];
    }
    const byDate = new Map(report.days.map(d => [d.date, d.revenue]));
    const days = [];
    for (let back = 6; back >= 0; back--) {
      const date = new Date();
      date.setDate(date.getDate() - back);
      const revenue = byDate.get(iso(date)) ?? 0;
      days.push({ date: iso(date), name: back === 0 ? "auj." : WEEKDAYS[date.getDay()].slice(0, 3) + '.', revenue, now: back === 0 });
    }
    const top = Math.max(1, ...days.map(d => d.revenue));
    return days.map(d => ({ ...d, height: (d.revenue / top) * 82 }));
  });

  constructor() {
    const fail = (e: unknown) => this.error.set(errorMessage(e));
    this.api.home().subscribe({ next: settings => this.settings.set(settings), error: fail });
    this.api.sales().subscribe({ next: sales => this.sales.set(sales), error: fail });
    this.api.cashStatus().subscribe({ next: cash => this.cash.set(cash), error: fail });
    this.api.products(true).subscribe({ next: products => this.lowStock.set(products.length), error: fail });
    this.api.quotes().subscribe({
      next: quotes => this.openQuotes.set(quotes.filter(q => q.status === 'OUVERT').length),
      error: fail,
    });
    if (this.auth.isManager()) {
      const now = new Date();
      const from = new Date();
      from.setDate(now.getDate() - 6);
      this.api.periodReport(iso(from), iso(now)).subscribe({ next: week => this.week.set(week), error: fail });
      this.api.creditReport().subscribe({
        next: credits => this.creditsToChase.set(credits.customers.filter(c => c.daysWaiting >= 30).length),
        error: fail,
      });
      this.api.suppliers().subscribe({
        next: suppliers => this.supplierDebt.set(suppliers.reduce((sum, s) => sum + Math.max(s.balance, 0), 0)),
        error: fail,
      });
    }
  }

  /** Montant abrégé au-dessus d'une barre : 12 500 devient « 12,5 k ». */
  protected short(amount: number): string {
    if (amount >= 1_000_000) {
      return `${(amount / 1_000_000).toFixed(1).replace('.', ',').replace(',0', '')} M`;
    }
    return amount >= 1000 ? `${(amount / 1000).toFixed(1).replace('.', ',').replace(',0', '')} k` : String(amount);
  }

  private longDate(date: Date): string {
    const weekday = WEEKDAYS[date.getDay()];
    return `${weekday.charAt(0).toUpperCase()}${weekday.slice(1)} ${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}`;
  }
}

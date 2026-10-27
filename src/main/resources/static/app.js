/**
 * PlayBox Management Platform - Frontend Application Logic
 * Single Page Application for monitoring and managing claw machine operations.
 */

// Global State
const state = {
  authToken: localStorage.getItem('playbox_session_token') || null,
  currentUser: localStorage.getItem('playbox_user') || 'admin',
  apiKey: localStorage.getItem('playbox_api_key') || 'tR3vM5o7ISXoDnEXzCHMa2gTnMlrlpxJ47urY5NCY7rFpjp3710Rj4wfDNJnPZdR',
  activeTab: 'dashboard',
  currentPeriod: 'hoje',
  currentCity: '',
  currentMachineId: '',
  currentStoreId: null,
  cities: [],
  dashboardData: null,
  monitorStatus: null,
  machines: [],
  stores: [],
  stockData: null,
  coletas: [],
  alugueis: { pendentes: [], pagos: [] },
  transacoes: [],
  transacoesPeriod: 'mes',
  transacoesCustomStart: null,
  transacoesCustomEnd: null,
  transacoesFilteredList: [],
  relatoriosAgendados: [],
  charts: {
    timeline: null,
    paymentMethods: null,
    storesRanking: null
  }
};

// ============================================================================
// API CLIENT & AUTH
// ============================================================================

async function apiRequest(endpoint, options = {}) {
  const headers = {
    ...(options.headers || {})
  };

  if (state.authToken) {
    headers['Authorization'] = `Bearer ${state.authToken}`;
  } else if (state.apiKey) {
    headers['X-API-KEY'] = state.apiKey;
  }

  if (options.body && typeof options.body === 'object' && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(options.body);
  }

  try {
    const response = await fetch(endpoint, { ...options, headers });

    if (response.status === 401) {
      if (!endpoint.includes('/api/auth/login')) {
        showAuthScreen();
        showToast('Sessão expirada. Faça login novamente.', 'warning');
      }
      throw new Error('UNAUTHORIZED');
    }

    if (!response.ok) {
      const errorText = await response.text();
      let errorMsg = `Erro ${response.status}: ${response.statusText}`;
      try {
        const errorJson = JSON.parse(errorText);
        if (errorJson.message) errorMsg = errorJson.message;
        if (errorJson.erro) errorMsg = errorJson.erro;
      } catch (e) {}
      throw new Error(errorMsg);
    }

    updateApiStatusBadge(true, 'Conectado');

    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/pdf')) {
      return await response.blob();
    }
    if (contentType && contentType.includes('application/json')) {
      return await response.json();
    }
    return await response.text();
  } catch (err) {
    if (err.message !== 'UNAUTHORIZED') {
      console.error(`Erro ao chamar ${endpoint}:`, err);
    }
    throw err;
  }
}

function updateApiStatusBadge(connected, text) {
  const dot = document.getElementById('api-status-dot');
  const label = document.getElementById('api-status-text');
  if (connected) {
    dot.className = 'w-2 h-2 rounded-full bg-emerald-500 pulse-dot-green';
    label.textContent = text || 'API Conectada';
    label.className = 'text-slate-600 font-semibold hidden md:inline';
  } else {
    dot.className = 'w-2 h-2 rounded-full bg-rose-500 pulse-dot-red';
    label.textContent = text || 'Desconectado';
    label.className = 'text-rose-600 hidden md:inline font-bold';
  }
}

function openApiKeyModal() {
  document.getElementById('input-api-key').value = state.apiKey;
  document.getElementById('api-modal-status').textContent = 'Chave atual carregada';
  document.getElementById('api-modal-status').className = 'text-slate-500 font-medium';
  openModal('modal-api-key');
}

async function testApiKey() {
  const key = document.getElementById('input-api-key').value.trim();
  const statusEl = document.getElementById('api-modal-status');
  statusEl.textContent = 'Testando...';
  statusEl.className = 'text-pink-600 font-semibold';

  try {
    const res = await fetch('/health');
    const health = await res.json();
    
    // Now test authorized endpoint
    const testRes = await fetch('/api/maquinas', {
      headers: { 'X-API-KEY': key }
    });

    if (testRes.ok) {
      statusEl.textContent = '✓ Conexão bem-sucedida!';
      statusEl.className = 'text-emerald-600 font-bold';
    } else {
      statusEl.textContent = '✗ Chave rejeitada pelo servidor';
      statusEl.className = 'text-rose-600 font-bold';
    }
  } catch (err) {
    statusEl.textContent = '✗ Servidor inacessível';
    statusEl.className = 'text-rose-600 font-bold';
  }
}

function saveApiKey() {
  const key = document.getElementById('input-api-key').value.trim();
  if (!key) {
    showToast('Informe a chave de API.', 'error');
    return;
  }
  state.apiKey = key;
  localStorage.setItem('playbox_api_key', key);
  closeModal('modal-api-key');
  showToast('Chave de API salva com sucesso!', 'success');
  refreshActiveTab(true);
}

// ============================================================================
// NAVIGATION & TABS (RESPONSIVE COLLAPSIBLE SIDEBAR)
// ============================================================================

const TAB_METADATA = {
  dashboard: { title: 'Visão Geral', subtitle: 'Gestão consolidada e métricas em tempo real' },
  maquinas: { title: 'Máquinas & Status', subtitle: 'Telemetria e monitoramento de conectividade' },
  estoque: { title: 'Estoque Pelúcias', subtitle: 'Controle de níveis e reposição física' },
  coletas: { title: 'Coleta Espécie', subtitle: 'Auditoria de notas e moedas físicas' },
  alugueis: { title: 'Aluguéis & Repasses', subtitle: 'Contratos fixos e dinâmicos com pontos comerciais' },
  transacoes: { title: 'Transações & Testes', subtitle: 'Auditoria financeira e desconsideração de testes' },
  estabelecimentos: { title: 'Pontos Comerciais', subtitle: 'Lojas parceiras e histórico de máquinas' },
  relatorios: { title: 'Relatório PDF', subtitle: 'Fechamentos mensais e extratos executivos' },
  configuracoes: { title: 'Agendamentos & WhatsApp', subtitle: 'Personalização de disparos e relatórios dinâmicos' }
};

function toggleSidebar() {
  const sidebar = document.getElementById('app-sidebar');
  const backdrop = document.getElementById('sidebar-backdrop');
  if (!sidebar) return;

  if (window.innerWidth < 1024) {
    // Mobile Drawer
    const isOpen = sidebar.classList.contains('mobile-open');
    if (isOpen) {
      closeSidebarMobile();
    } else {
      sidebar.classList.add('mobile-open');
      sidebar.classList.remove('-translate-x-full');
      if (backdrop) backdrop.classList.remove('hidden');
    }
  } else {
    // Desktop Collapse (rail icon mode)
    sidebar.classList.toggle('sidebar-collapsed');
    const isCollapsed = sidebar.classList.contains('sidebar-collapsed');
    localStorage.setItem('playbox_sidebar_collapsed', isCollapsed ? 'true' : 'false');

    const iconOpen = document.querySelector('.sidebar-icon-open');
    const iconClosed = document.querySelector('.sidebar-icon-closed');
    if (iconOpen && iconClosed) {
      if (isCollapsed) {
        iconOpen.classList.add('hidden');
        iconClosed.classList.remove('hidden');
      } else {
        iconOpen.classList.remove('hidden');
        iconClosed.classList.add('hidden');
      }
    }
  }

  if (window.lucide) lucide.createIcons();
}

function closeSidebarMobile() {
  const sidebar = document.getElementById('app-sidebar');
  const backdrop = document.getElementById('sidebar-backdrop');
  if (sidebar) {
    sidebar.classList.remove('mobile-open');
    if (window.innerWidth < 1024) {
      sidebar.classList.add('-translate-x-full');
    }
  }
  if (backdrop) backdrop.classList.add('hidden');
}

function initSidebar() {
  const sidebar = document.getElementById('app-sidebar');
  if (!sidebar) return;

  if (window.innerWidth >= 1024 && localStorage.getItem('playbox_sidebar_collapsed') === 'true') {
    sidebar.classList.add('sidebar-collapsed');
    const iconOpen = document.querySelector('.sidebar-icon-open');
    const iconClosed = document.querySelector('.sidebar-icon-closed');
    if (iconOpen && iconClosed) {
      iconOpen.classList.add('hidden');
      iconClosed.classList.remove('hidden');
    }
  }
}

function switchTab(tabId) {
  state.activeTab = tabId;

  // Fechar sidebar mobile ao clicar em um item
  closeSidebarMobile();

  // Atualizar título e subtítulo no topo da página
  const meta = TAB_METADATA[tabId] || { title: 'PlayBox', subtitle: 'Gestão de Operações' };
  const titleEl = document.getElementById('page-current-title');
  const subtitleEl = document.getElementById('page-current-subtitle');
  if (titleEl) titleEl.innerHTML = `<span>${meta.title}</span>`;
  if (subtitleEl) subtitleEl.textContent = meta.subtitle;

  // Update tabs buttons styling
  document.querySelectorAll('.tab-btn').forEach(btn => {
    if (btn.getAttribute('data-tab') === tabId) {
      btn.classList.add('active-tab');
      btn.classList.remove('text-slate-600');
    } else {
      btn.classList.remove('active-tab');
      btn.classList.add('text-slate-600');
    }
  });

  // Toggle pane visibility
  document.querySelectorAll('.tab-pane').forEach(pane => {
    pane.classList.add('hidden');
  });

  const targetPane = document.getElementById(`tab-${tabId}`);
  if (targetPane) {
    targetPane.classList.remove('hidden');
  }

  // Load relevant data for tab
  switch (tabId) {
    case 'dashboard':
      loadDashboardData();
      break;
    case 'maquinas':
      loadMaquinasData();
      break;
    case 'estoque':
      loadEstoqueData();
      break;
    case 'coletas':
      loadColetasData();
      break;
    case 'alugueis':
      loadAlugueisData();
      break;
    case 'transacoes':
      loadTransacoesData();
      break;
    case 'estabelecimentos':
      loadEstabelecimentosData();
      break;
    case 'relatorios':
      previewRelatorioOperacoes();
      break;
    case 'configuracoes':
      loadConfiguracoesRelatorio();
      break;
  }

  // Re-run Lucide icons
  if (window.lucide) {
    lucide.createIcons();
  }
}

async function refreshActiveTab(showNotification = false) {
  const icon = document.getElementById('btn-sync-icon');
  if (icon) icon.classList.add('animate-spin');

  try {
    await loadInitialLookups();
    await switchTab(state.activeTab);
    if (showNotification) {
      showToast('Dados atualizados com sucesso!', 'success');
    }
  } catch (err) {
    showToast('Falha ao atualizar dados: ' + err.message, 'error');
  } finally {
    if (icon) icon.classList.remove('animate-spin');
  }
}

// ============================================================================
// DASHBOARD VIEW
// ============================================================================

function setDashboardPeriod(period) {
  state.currentPeriod = period;
  document.querySelectorAll('#period-buttons-group .filter-btn').forEach(btn => {
    if (btn.getAttribute('data-period') === period) {
      btn.classList.add('active');
    } else {
      btn.classList.remove('active');
    }
  });

  const customBar = document.getElementById('custom-dates-bar');
  if (period === 'custom') {
    if (customBar) customBar.classList.remove('hidden');
    const now = new Date();
    const todayStr = now.toISOString().split('T')[0];
    const firstDay = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().split('T')[0];
    const startInput = document.getElementById('dash-date-start');
    const endInput = document.getElementById('dash-date-end');
    if (startInput && !startInput.value) startInput.value = firstDay;
    if (endInput && !endInput.value) endInput.value = todayStr;
  } else {
    if (customBar) customBar.classList.add('hidden');
    loadDashboardData();
  }
}

function onDashboardPeriodSelect(period) {
  setDashboardPeriod(period);
}

function toggleCustomDates() {
  const customBar = document.getElementById('custom-dates-bar');
  if (customBar) customBar.classList.toggle('hidden');
  state.currentPeriod = 'custom';
  document.querySelectorAll('#period-buttons-group .filter-btn').forEach(btn => {
    if (btn.getAttribute('data-period') === 'custom') {
      btn.classList.add('active');
    } else {
      btn.classList.remove('active');
    }
  });
}

function applyCustomDates() {
  const start = document.getElementById('dash-date-start')?.value;
  const end = document.getElementById('dash-date-end')?.value;

  if (!start || !end) {
    showToast('Selecione as datas de início e fim.', 'warning');
    return;
  }
  loadDashboardData();
}

function setDashboardCity(city) {
  state.currentCity = city || '';
  document.querySelectorAll('#city-buttons-group .filter-btn').forEach(btn => {
    if ((btn.getAttribute('data-city') || '') === state.currentCity) {
      btn.classList.add('active');
    } else {
      btn.classList.remove('active');
    }
  });

  // Filter machine select to only show machines from this city
  filterMachineSelectByCity(state.currentCity);

  loadDashboardData();
}

function onMachineSelect(machineId) {
  state.currentMachineId = machineId || '';
  loadDashboardData();
}

function filterMachineSelectByCity(city) {
  const select = document.getElementById('dash-filter-machine');
  if (!select) return;

  const filtered = (state.machines || []).filter(m => {
    if (!city) return true;
    const mCity = m.estabelecimento?.cidade || m.cidade || '';
    return mCity.trim().toLowerCase() === city.trim().toLowerCase();
  });

  const cur = state.currentMachineId;
  let html = '<option value="">Todas as Máquinas</option>';
  filtered.forEach(m => {
    const name = m.nomeIntegracao || m.descricao || `Máquina #${m.id}`;
    const store = m.estabelecimento?.nome ? ` (${m.estabelecimento.nome})` : '';
    html += `<option value="${m.id}" ${String(m.id) === String(cur) ? 'selected' : ''}>${name}${store}</option>`;
  });
  select.innerHTML = html;
}

function filterByStore(storeId) {
  state.currentStoreId = storeId;
  const store = (state.stores || []).find(s => s.id === storeId);
  const badge = document.getElementById('filter-store-active-badge');
  const nameEl = document.getElementById('filter-store-active-name');
  if (badge && nameEl) {
    nameEl.textContent = `Filtrando por: ${store ? store.nome : 'Estabelecimento #' + storeId}`;
    badge.classList.remove('hidden');
  }
  loadDashboardData();
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function clearStoreFilter() {
  state.currentStoreId = null;
  const badge = document.getElementById('filter-store-active-badge');
  if (badge) badge.classList.add('hidden');
  loadDashboardData();
}

async function loadDashboardData(syncForce = false) {
  try {
    let url = `/api/relatorios?periodo=${state.currentPeriod}`;

    if (state.currentPeriod === 'custom') {
      const start = document.getElementById('dash-date-start')?.value;
      const end = document.getElementById('dash-date-end')?.value;
      if (start) url += `&dataInicio=${start}`;
      if (end) url += `&dataFim=${end}`;
    }

    if (state.currentCity) {
      url += `&cidade=${encodeURIComponent(state.currentCity)}`;
    }

    if (state.currentMachineId) {
      url += `&maquinaId=${state.currentMachineId}`;
    }

    if (state.currentStoreId) {
      url += `&estabelecimentoId=${state.currentStoreId}`;
    }

    if (syncForce) {
      url += `&sincronizar=true`;
      showToast('Sincronizando transações das integradoras...', 'info');
    }

    // Parallel fetch: report & offline monitor status
    const [report, monitorStatus, stock, pendingColetas] = await Promise.all([
      apiRequest(url),
      apiRequest('/api/monitor/status').catch(() => ({ totalOffline: 0, maquinasOffline: [] })),
      apiRequest('/api/estoque').catch(() => ({ resumo: {} })),
      apiRequest('/api/coletas/pendentes').catch(() => [])
    ]);

    state.dashboardData = report;
    state.monitorStatus = monitorStatus;

    // Update Badges on top navigation
    updateGlobalBadges(monitorStatus, stock, pendingColetas);

    // Render Dashboard Offline Banner
    renderDashboardOfflineBanner(monitorStatus);

    // Render KPIs
    renderDashboardKPIs(report, monitorStatus);

    // Render Stores Ranking List (DESTAQUE FATURAMENTO POR LOJA EM LISTA)
    renderStoresRankingList(report.porEstabelecimento || [], report.resumoGeral?.faturamentoBruto || 0);

    // Render Payment Methods Chart (FORMATO IMAGEM 1)
    renderPaymentMethodsChart(report.resumoGeral?.formasPagamento || {});

    // Render Receita por Pelúcia por Estabelecimento Chart
    renderReceitaPremioChart(report.porEstabelecimento || [], report.resumoGeral?.receitaPorPremioLiberado || 0);

    document.getElementById('dash-period-title').textContent = report.titulo || 'Consolidado';
    document.getElementById('dash-last-updated').textContent = `Atualizado às ${new Date().toLocaleTimeString('pt-BR')}`;

    if (window.lucide) lucide.createIcons();
  } catch (err) {
    showToast('Erro ao carregar dados do dashboard: ' + err.message, 'error');
  }
}

function updateGlobalBadges(monitor, stock, coletas) {
  const offlineBadge = document.getElementById('badge-offline-count');
  if (monitor && monitor.totalOffline > 0) {
    offlineBadge.textContent = monitor.totalOffline;
    offlineBadge.classList.remove('hidden');
  } else {
    offlineBadge.classList.add('hidden');
  }

  const estoqueBadge = document.getElementById('badge-estoque-baixo');
  if (stock && stock.resumo && stock.resumo.maquinasEmAlerta > 0) {
    estoqueBadge.textContent = stock.resumo.maquinasEmAlerta;
    estoqueBadge.classList.remove('hidden');
  } else {
    estoqueBadge.classList.add('hidden');
  }

  const coletasBadge = document.getElementById('badge-coletas-pendentes');
  if (coletas && coletas.length > 0) {
    coletasBadge.textContent = coletas.length;
    coletasBadge.classList.remove('hidden');
  } else {
    coletasBadge.classList.add('hidden');
  }
}

function renderDashboardKPIs(report, monitor) {
  const resumo = report.resumoGeral || {};
  const bruto = resumo.faturamentoBruto || 0;
  const estornos = resumo.totalEstornos || 0;
  const liquido = resumo.faturamentoLiquido || 0;
  const premios = resumo.quantidadePremios || 0;
  const recPremio = resumo.receitaPorPremioLiberado || 0;
  const formas = resumo.formasPagamento || {};
  const especie = formas.especie || 0;
  const pix = formas.pix || 0;
  const cartao = (formas.credito || 0) + (formas.debito || 0);

  document.getElementById('kpi-faturamento-bruto').textContent = formatMoney(bruto);
  document.getElementById('kpi-faturamento-liquido').textContent = formatMoney(liquido);
  document.getElementById('kpi-estornos').textContent = `Estornos: ${formatMoney(estornos)}`;

  const testes = resumo.totalTestesDesconsiderados || 0;
  const elTestes = document.getElementById('kpi-testes-desconsiderados');
  if (elTestes) {
    if (testes > 0) {
      elTestes.textContent = `Testes desc.: -${formatMoney(testes)}`;
      elTestes.classList.remove('hidden');
    } else {
      elTestes.classList.add('hidden');
    }
  }

  document.getElementById('kpi-premios').textContent = `${Math.round(premios)} un`;
  document.getElementById('kpi-rec-premio').textContent = `${formatMoney(recPremio)}/pelúcia`;
  document.getElementById('kpi-especie').textContent = formatMoney(especie);
  
  const especiePerc = bruto > 0 ? Math.round((especie / bruto) * 100) : 0;
  document.getElementById('kpi-especie-perc').textContent = `${especiePerc}% do faturamento`;

  document.getElementById('kpi-digital').textContent = formatMoney(pix + cartao);
  document.getElementById('kpi-digital-detail').textContent = `Pix: ${formatMoney(pix)} | Cartão: ${formatMoney(cartao)}`;

  // Telemetria status
  const iconWrap = document.getElementById('kpi-status-icon-wrap');
  const statusEl = document.getElementById('kpi-maquinas-status');
  const detailEl = document.getElementById('kpi-offline-detail');

  const offlineCount = monitor?.totalOffline || 0;
  if (offlineCount > 0) {
    iconWrap.className = 'p-1.5 rounded-lg bg-rose-500/20 text-rose-400';
    statusEl.innerHTML = `<span class="text-rose-400 font-bold">${offlineCount} OFFLINE</span>`;
    detailEl.textContent = 'Clique para verificar detalhes';
  } else {
    iconWrap.className = 'p-1.5 rounded-lg bg-emerald-500/10 text-emerald-400';
    statusEl.innerHTML = `<span class="text-emerald-400 font-bold">100% ONLINE</span>`;
    detailEl.textContent = 'Todas operando normalmente';
  }
}

// ============================================================================
// CHARTS (Chart.js)
// ============================================================================

function renderTimelineChart(dailyData) {
  const ctx = document.getElementById('chart-timeline');
  if (!ctx) return;

  if (state.charts.timeline) {
    state.charts.timeline.destroy();
  }

  const labels = dailyData.map(d => d.dataFormatada || d.data);
  const dataBruto = dailyData.map(d => d.faturamentoBruto || 0);
  const dataLiquido = dailyData.map(d => d.faturamentoLiquido || 0);
  const dataPremios = dailyData.map(d => d.quantidadePremios || 0);

  state.charts.timeline = new Chart(ctx, {
    type: 'line',
    data: {
      labels: labels.length > 0 ? labels : ['Sem dados no período'],
      datasets: [
        {
          label: 'Fat. Bruto (R$)',
          data: dataBruto,
          borderColor: '#db2777',
          backgroundColor: 'rgba(219, 39, 119, 0.08)',
          fill: true,
          tension: 0.3,
          borderWidth: 2.5,
          pointRadius: 3,
          pointBackgroundColor: '#db2777',
          yAxisID: 'y'
        },
        {
          label: 'Fat. Líquido (R$)',
          data: dataLiquido,
          borderColor: '#10b981',
          borderDash: [4, 4],
          tension: 0.3,
          borderWidth: 2,
          pointRadius: 2,
          pointBackgroundColor: '#10b981',
          yAxisID: 'y'
        },
        {
          label: 'Prêmios (un)',
          data: dataPremios,
          borderColor: '#06b6d4',
          backgroundColor: 'transparent',
          tension: 0.3,
          borderWidth: 2,
          pointRadius: 3,
          pointBackgroundColor: '#06b6d4',
          yAxisID: 'y1'
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: {
        mode: 'index',
        intersect: false
      },
      plugins: {
        legend: {
          labels: { color: '#475569', font: { size: 11, weight: '600' } }
        },
        tooltip: {
          backgroundColor: '#0f172a',
          titleColor: '#ffffff',
          bodyColor: '#e2e8f0',
          borderColor: '#e2e8f0',
          borderWidth: 1,
          callbacks: {
            label: function(context) {
              if (context.dataset.yAxisID === 'y1') {
                return `${context.dataset.label}: ${context.raw} un`;
              }
              return `${context.dataset.label}: ${formatMoney(context.raw)}`;
            }
          }
        }
      },
      scales: {
        x: {
          grid: { color: 'rgba(0, 0, 0, 0.04)' },
          ticks: { color: '#64748b', font: { size: 10 } }
        },
        y: {
          type: 'linear',
          display: true,
          position: 'left',
          grid: { color: 'rgba(0, 0, 0, 0.05)' },
          ticks: {
            color: '#64748b',
            font: { size: 10 },
            callback: value => 'R$ ' + value
          }
        },
        y1: {
          type: 'linear',
          display: true,
          position: 'right',
          grid: { drawOnChartArea: false },
          ticks: {
            color: '#0891b2',
            font: { size: 10, weight: 'bold' },
            callback: value => value + ' un'
          }
        }
      }
    }
  });
}

function renderPaymentMethodsChart(formas) {
  const ctx = document.getElementById('chart-payment-methods');
  if (!ctx) return;

  const especie = formas.especie || 0;
  const pix = formas.pix || 0;
  const credito = formas.credito || 0;
  const debito = formas.debito || 0;
  const total = especie + pix + credito + debito;

  // Update visible values directly in the DOM (matches Reference Image 1)
  const totalCenterEl = document.getElementById('payment-total-center');
  if (totalCenterEl) totalCenterEl.textContent = formatMoney(total);

  const pixEl = document.getElementById('val-pay-pix');
  if (pixEl) pixEl.textContent = formatMoney(pix);

  const especieEl = document.getElementById('val-pay-especie');
  if (especieEl) especieEl.textContent = formatMoney(especie);

  const creditoEl = document.getElementById('val-pay-credito');
  if (creditoEl) creditoEl.textContent = formatMoney(credito);

  const debitoEl = document.getElementById('val-pay-debito');
  if (debitoEl) debitoEl.textContent = formatMoney(debito);

  // Update visible percentage badges
  const calcPerc = (val) => (total > 0 ? `${Math.round((val / total) * 100)}%` : '0%');
  const percPixEl = document.getElementById('perc-pay-pix');
  if (percPixEl) percPixEl.textContent = calcPerc(pix);
  const percEspEl = document.getElementById('perc-pay-especie');
  if (percEspEl) percEspEl.textContent = calcPerc(especie);
  const percCredEl = document.getElementById('perc-pay-credito');
  if (percCredEl) percCredEl.textContent = calcPerc(credito);
  const percDebEl = document.getElementById('perc-pay-debito');
  if (percDebEl) percDebEl.textContent = calcPerc(debito);

  if (state.charts.paymentMethods) {
    state.charts.paymentMethods.destroy();
  }

  // If total is 0, render a light gray empty placeholder ring
  const hasData = total > 0;
  const chartData = hasData ? [pix, especie, credito, debito] : [1];
  const chartColors = hasData ? ['#db2777', '#f59e0b', '#06b6d4', '#8b5cf6'] : ['#f1f5f9'];
  const labels = hasData ? ['Pix', 'Espécie', 'Crédito', 'Débito'] : ['Sem Vendas'];

  state.charts.paymentMethods = new Chart(ctx, {
    type: 'doughnut',
    data: {
      labels: labels,
      datasets: [
        {
          data: chartData,
          backgroundColor: chartColors,
          borderColor: '#ffffff',
          borderWidth: hasData ? 3 : 0,
          hoverOffset: hasData ? 4 : 0
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          display: false // Visible legend list is already rendered on the right
        },
        tooltip: {
          enabled: hasData,
          backgroundColor: '#0f172a',
          titleColor: '#ffffff',
          bodyColor: '#e2e8f0',
          borderColor: '#e2e8f0',
          borderWidth: 1,
          callbacks: {
            label: function(context) {
              const val = context.raw || 0;
              const perc = total > 0 ? Math.round((val / total) * 100) : 0;
              return `${context.label}: ${formatMoney(val)} (${perc}%)`;
            }
          }
        }
      },
      cutout: '74%'
    }
  });
}

function renderDashboardOfflineBanner(monitor) {
  const container = document.getElementById('dash-offline-list-container');
  const badge = document.getElementById('dash-offline-badge');
  if (!container) return;

  const offList = monitor?.maquinasOffline || [];
  const offCount = monitor?.totalOffline || offList.length;

  if (badge) {
    if (offCount > 0) {
      badge.className = 'px-2.5 py-1 rounded-full text-xs font-bold bg-amber-50 text-amber-800 border border-amber-200';
      badge.textContent = `${offCount} offline`;
    } else {
      badge.className = 'px-2.5 py-1 rounded-full text-xs font-bold bg-emerald-50 text-emerald-800 border border-emerald-200';
      badge.textContent = `100% online`;
    }
  }

  if (offCount === 0 || offList.length === 0) {
    container.innerHTML = `
      <div class="h-full flex flex-col items-center justify-center py-6 text-center text-slate-400">
        <div class="w-10 h-10 rounded-2xl bg-emerald-50 text-emerald-600 flex items-center justify-center mb-2 shadow-xs">
          <i data-lucide="check-circle-2" class="w-5 h-5"></i>
        </div>
        <p class="text-xs font-bold text-slate-700">Todas as máquinas online</p>
        <p class="text-[11px] text-slate-400 mt-0.5">Nenhuma máquina offline detectada.</p>
      </div>
    `;
    return;
  }

  // Renderiza lista direta com status OFFLINE
  container.innerHTML = offList.map(m => `
    <div class="flex items-center justify-between p-2.5 rounded-xl border border-rose-200/80 bg-rose-50/40 hover:bg-white hover:border-rose-300 transition text-xs">
      <div class="flex items-center gap-2.5 min-w-0">
        <span class="w-2.5 h-2.5 rounded-full bg-rose-500 shrink-0"></span>
        <div class="min-w-0">
          <div class="font-bold text-slate-800 truncate">${m.nome}</div>
          ${m.offlineDesde ? `<div class="text-[11px] text-slate-500">Offline desde ${m.offlineDesde}</div>` : ''}
        </div>
      </div>
      <span class="px-2 py-0.5 rounded-md text-[10px] font-bold bg-rose-100 text-rose-700 border border-rose-200 shrink-0">
        OFFLINE
      </span>
    </div>
  `).join('');
}

function renderReceitaPremioChart(stores, mediaGeral) {
  const ctx = document.getElementById('chart-receita-premio');
  const mediaBadge = document.getElementById('receita-premio-media-badge');
  if (!ctx) return;

  if (mediaBadge) {
    mediaBadge.textContent = `Média Geral: ${formatMoney(mediaGeral || 0)}/un`;
  }

  if (state.charts.receitaPremio) {
    state.charts.receitaPremio.destroy();
  }

  // Filtrar apenas lojas com pelúcias liberadas ou faturamento
  const lojasComDados = (stores || [])
    .filter(s => (s.faturamentoBruto || 0) > 0 || (s.quantidadePremios || 0) > 0)
    .sort((a, b) => (b.receitaPorPremio || 0) - (a.receitaPorPremio || 0));

  if (lojasComDados.length === 0) {
    state.charts.receitaPremio = new Chart(ctx, {
      type: 'bar',
      data: {
        labels: ['Sem dados de prêmios no período'],
        datasets: [{ data: [0], backgroundColor: '#e2e8f0' }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false } }
      }
    });
    return;
  }

  const labels = lojasComDados.map(s => s.nome || 'Sem Estabelecimento');
  const receitas = lojasComDados.map(s => s.receitaPorPremio || 0);
  const premios = lojasComDados.map(s => Math.round(s.quantidadePremios || 0));

  // Cores dinâmicas: verde quando saudável, ciano/azul padrão, e destaque
  const barColors = receitas.map(v => {
    if (v === 0) return '#cbd5e1'; // Sem prêmio liberado
    if (v < 25) return '#f59e0b';  // Custo por pelúcia muito baixo (muitas pelúcias saindo)
    if (v <= 50) return '#06b6d4'; // Faixa ideal
    return '#8b5cf6';             // Alta arrecadação por pelúcia
  });

  state.charts.receitaPremio = new Chart(ctx, {
    type: 'bar',
    data: {
      labels: labels,
      datasets: [
        {
          label: 'Receita por Pelúcia (R$)',
          data: receitas,
          backgroundColor: barColors,
          borderRadius: 8,
          borderSkipped: false,
          maxBarThickness: 45
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          display: false
        },
        tooltip: {
          backgroundColor: '#0f172a',
          titleColor: '#ffffff',
          bodyColor: '#e2e8f0',
          borderColor: '#e2e8f0',
          borderWidth: 1,
          padding: 10,
          callbacks: {
            label: function(context) {
              const idx = context.dataIndex;
              const val = context.raw || 0;
              const qtd = premios[idx] || 0;
              return [
                `Receita/Pelúcia: ${formatMoney(val)}`,
                `Total de Pelúcias: ${qtd} un`
              ];
            }
          }
        }
      },
      scales: {
        x: {
          grid: { display: false },
          ticks: {
            color: '#475569',
            font: { size: 11, weight: '600' },
            maxRotation: 45,
            minRotation: 0
          }
        },
        y: {
          beginAtZero: true,
          grid: { color: 'rgba(0, 0, 0, 0.05)' },
          ticks: {
            color: '#64748b',
            font: { size: 11 },
            callback: value => 'R$ ' + value
          }
        }
      }
    }
  });
}

function renderStoresRankingList(stores, totalGeral) {
  const container = document.getElementById('stores-ranking-container');
  const countBadge = document.getElementById('stores-count-badge');
  if (!container) return;

  const validStores = (stores || []).filter(s => (s.faturamentoBruto || 0) > 0 || (s.quantidadePremios || 0) > 0);
  const sorted = [...(stores || [])].sort((a, b) => (b.faturamentoBruto || 0) - (a.faturamentoBruto || 0));

  if (countBadge) {
    countBadge.textContent = `${sorted.length} estabelecimentos (${validStores.length} com vendas)`;
  }

  if (sorted.length === 0) {
    container.innerHTML = `
      <div class="text-center py-8 text-slate-400 bg-slate-50 rounded-2xl border border-dashed border-slate-200">
        <i data-lucide="store" class="w-8 h-8 mx-auto mb-2 text-slate-300"></i>
        <p class="text-sm font-semibold text-slate-600">Nenhum faturamento registrado no período selecionado.</p>
        <p class="text-xs text-slate-400 mt-1">Selecione outro período (ex: Últimos 7 dias ou Mês) ou clique em Sincronizar.</p>
      </div>
    `;
    if (window.lucide) lucide.createIcons();
    return;
  }

  let html = '';
  sorted.forEach((s, idx) => {
    const bruto = s.faturamentoBruto || 0;
    const perc = totalGeral > 0 ? Math.round((bruto / totalGeral) * 100) : 0;
    const especie = s.formasPagamento?.especie || 0;
    const pix = s.formasPagamento?.pix || 0;
    const cartao = (s.formasPagamento?.credito || 0) + (s.formasPagamento?.debito || 0);
    const premios = Math.round(s.quantidadePremios || 0);

    let rankCircleClass = 'rank-circle-other';
    let rankValClass = 'rank-val-other';
    if (idx === 0) {
      rankCircleClass = 'rank-circle-1';
      rankValClass = 'rank-val-1';
    } else if (idx === 1) {
      rankCircleClass = 'rank-circle-2';
      rankValClass = 'rank-val-2';
    } else if (idx === 2) {
      rankCircleClass = 'rank-circle-3';
      rankValClass = 'rank-val-3';
    }

    const storeName = s.nome || 'Sem Estabelecimento';
    const isSemEst = !s.estabelecimentoId || storeName === 'Sem Estabelecimento';
    const cidadeStr = s.cidade ? ` • ${s.cidade}` : '';
    const maquinasQtd = s.quantidadeMaquinas || 1;
    const isFiltered = state.currentStoreId && state.currentStoreId === s.estabelecimentoId;
    const maquinasDet = (s.maquinasInfo && s.maquinasInfo.length > 0)
      ? s.maquinasInfo.join(', ')
      : '';

    html += `
      <div class="flex items-center justify-between p-3 sm:p-3.5 rounded-xl border ${isFiltered ? 'border-pink-500 bg-pink-50/50 ring-1 ring-pink-500' : (isSemEst ? 'border-amber-300/80 bg-amber-50/30 hover:border-amber-400' : 'border-slate-200/80 bg-white hover:border-pink-300')} hover:shadow-sm transition gap-3">
        <div class="flex items-center gap-3 min-w-0">
          <!-- Circular Badge matching Reference Image 3 -->
          <div class="w-8 h-8 sm:w-9 sm:h-9 rounded-full flex items-center justify-center font-black text-xs sm:text-sm shrink-0 shadow-sm ${rankCircleClass}">
            ${idx + 1}
          </div>
          <div class="min-w-0">
            <div class="font-extrabold ${isSemEst ? 'text-amber-700' : 'text-slate-900'} text-xs sm:text-sm truncate hover:text-pink-600 transition cursor-pointer" onclick="${s.estabelecimentoId ? `filterByStore(${s.estabelecimentoId})` : `switchTab('maquinas')`}" title="${s.estabelecimentoId ? 'Clique para filtrar apenas esta loja' : 'Clique para ir para tela de Máquinas e associar'}">
              ${storeName} ${isSemEst ? '<span class="text-[10px] font-bold text-amber-600 bg-amber-100 px-1.5 py-0.5 rounded ml-1">Requer Associação</span>' : ''}
            </div>
            <div class="text-[11px] text-slate-500 flex items-center gap-1.5 flex-wrap mt-0.5">
              ${s.estabelecimentoId ? `<span>ID: ${s.estabelecimentoId}</span>` : ''}
              ${cidadeStr ? `<span>${cidadeStr}</span>` : ''}
              <span>• ${maquinasQtd} máq.</span>
              ${maquinasDet ? `<span class="text-slate-700 font-medium bg-slate-100 px-1.5 py-0.5 rounded text-[10px] truncate max-w-xs" title="${maquinasDet}">⚙️ ${maquinasDet}</span>` : ''}
              ${premios > 0 ? `<span class="font-semibold text-pink-600">• 🎁 ${premios} un</span>` : ''}
            </div>
          </div>
        </div>

        <div class="text-right shrink-0">
          <div class="text-sm sm:text-base font-black ${rankValClass}">
            ${formatMoney(bruto)}
          </div>
          <div class="text-[10px] sm:text-[11px] text-slate-400 font-medium">
            ${perc}% do total
          </div>
        </div>
      </div>
    `;
  });

  container.innerHTML = html;
  if (window.lucide) lucide.createIcons();
}

// ============================================================================
// OPERATIONAL TRIGGERS
// ============================================================================

async function triggerMonitor() {
  showToast('Iniciando checagem de telemetria das máquinas...', 'info');
  try {
    const res = await apiRequest('/api/trigger/monitor', { method: 'POST' });
    showToast('Monitoramento executado com sucesso!', 'success');
    loadDashboardData();
    if (state.activeTab === 'maquinas') loadMaquinasData();
  } catch (err) {
    showToast('Erro ao executar monitoramento: ' + err.message, 'error');
  }
}

async function triggerImportTransactions() {
  showToast('Sincronizando transações de ontem nas integradoras...', 'info');
  try {
    const res = await apiRequest('/api/trigger/import-transactions', { method: 'POST' });
    showToast(`Transações importadas! Registros: ${res.registrosImportados || 0}`, 'success');
    loadDashboardData();
  } catch (err) {
    showToast('Erro ao importar transações: ' + err.message, 'error');
  }
}

async function triggerEstoqueAlerta() {
  showToast('Verificando estoque crítico para envio ao WhatsApp...', 'info');
  try {
    const res = await apiRequest('/api/trigger/alerta-estoque', { method: 'POST' });
    showToast(`Alerta de estoque concluído! Máquinas notificadas: ${res.maquinasNotificadas || 0}`, 'success');
  } catch (err) {
    showToast('Erro ao disparar alerta de estoque: ' + err.message, 'error');
  }
}

async function triggerWhatsAppReport() {
  showToast('Gerando e enviando relatório de faturamento no WhatsApp...', 'info');
  try {
    await apiRequest('/api/trigger/report?periodo=24h-anterior', { method: 'POST' });
    showToast('Relatório enviado ao grupo com sucesso!', 'success');
  } catch (err) {
    showToast('Erro ao enviar relatório: ' + err.message, 'error');
  }
}

// ============================================================================
// TAB 2: MÁQUINAS & TELEMETRIA
// ============================================================================

async function loadMaquinasData() {
  try {
    const [machines, monitor] = await Promise.all([
      apiRequest('/api/maquinas'),
      apiRequest('/api/monitor/status').catch(() => ({ totalOffline: 0, maquinasOffline: [] }))
    ]);

    state.machines = machines;
    state.monitorStatus = monitor;

    // Render offline banner
    renderOfflineBanner(monitor);

    // Apply active filters and render machines grid
    filterMachines();

    if (window.lucide) lucide.createIcons();
  } catch (err) {
    showToast('Erro ao carregar máquinas: ' + err.message, 'error');
  }
}

function renderOfflineBanner(monitor) {
  const banner = document.getElementById('banner-offline-alert');
  const list = document.getElementById('banner-offline-list');

  if (monitor && monitor.totalOffline > 0 && monitor.maquinasOffline.length > 0) {
    banner.classList.remove('hidden');
    list.innerHTML = monitor.maquinasOffline.map(m => `
      <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md bg-rose-900/60 border border-rose-500/40 text-rose-200 text-xs">
        <span class="w-2 h-2 rounded-full bg-rose-400 pulse-dot-red"></span>
        <strong>${m.nome}</strong>
        <span class="text-rose-300 text-[11px]">(desde ${m.offlineDesde})</span>
      </span>
    `).join('');
  } else {
    banner.classList.add('hidden');
  }
}

function renderMachinesGrid(machines, monitor) {
  const grid = document.getElementById('machines-grid');
  if (!grid) return;

  const offlineMap = new Map();
  if (monitor?.maquinasOffline) {
    monitor.maquinasOffline.forEach(m => offlineMap.set(m.nome.toLowerCase().trim(), m.offlineDesde));
  }

  if (machines.length === 0) {
    grid.innerHTML = `<div class="col-span-full text-center py-12 text-slate-500 text-sm">Nenhuma máquina encontrada com os filtros selecionados.</div>`;
    return;
  }

  grid.innerHTML = machines.map(m => {
    const name = m.nomeIntegracao || m.descricao || `Máquina #${m.id}`;
    const cleanName = name.toLowerCase().trim();
    const est = m.estabelecimento;
    const estName = (est?.nome || '').toLowerCase().trim();
    const hasStore = !!est;
    const isActive = m.ativo !== false;

    // A machine without a store or inactive MUST NEVER be shown as OFFLINE
    let isOffline = false;
    let offlineSince = null;
    if (hasStore && isActive) {
      for (const [offName, ts] of offlineMap.entries()) {
        if (cleanName.includes(offName) || offName.includes(cleanName) ||
            (estName && (estName.includes(offName) || offName.includes(estName)))) {
          isOffline = true;
          offlineSince = ts;
          break;
        }
      }
    }

    const atual = m.estoqueAtual || 0;
    const cap = m.capacidadeMaxima || 80;
    const perc = cap > 0 ? Math.min(100, Math.round((atual / cap) * 100)) : 0;
    const isLowStock = atual < (m.estoqueMinimoAlerta || 30);

    let progressColor = 'bg-emerald-500';
    if (perc <= 30) progressColor = 'bg-rose-500';
    else if (perc <= 50) progressColor = 'bg-amber-500';

    // Status Badge
    let statusBadgeHtml = '';
    if (!isActive) {
      statusBadgeHtml = `
        <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-bold bg-slate-100 text-slate-500 border border-slate-200">
          <span class="w-2 h-2 rounded-full bg-slate-400"></span> INATIVA
        </span>
      `;
    } else if (!hasStore) {
      statusBadgeHtml = `
        <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-bold bg-amber-50 text-amber-700 border border-amber-200">
          <span class="w-2 h-2 rounded-full bg-amber-500"></span> SEM LOJA (DEPÓSITO)
        </span>
      `;
    } else if (isOffline) {
      statusBadgeHtml = `
        <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-bold bg-rose-100 text-rose-700 border border-rose-200">
          <span class="w-2 h-2 rounded-full bg-rose-500 pulse-dot-red"></span> OFFLINE
        </span>
      `;
    } else {
      statusBadgeHtml = `
        <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-bold bg-emerald-100 text-emerald-800 border border-emerald-200">
          <span class="w-2 h-2 rounded-full bg-emerald-500 pulse-dot-green"></span> ONLINE
        </span>
      `;
    }

    return `
      <div class="glass-card p-5 flex flex-col justify-between space-y-4 border ${isOffline ? 'border-rose-300 bg-rose-50/20' : (!isActive ? 'border-slate-200 bg-slate-50/60 opacity-80' : (!hasStore ? 'border-amber-200 bg-amber-50/15' : 'border-slate-200'))} shadow-sm">
        <div>
          <!-- Top Row: Name & Status -->
          <div class="flex items-start justify-between gap-2">
            <div>
              <div class="flex items-center gap-2">
                <span class="font-extrabold text-sm text-slate-900">${name}</span>
                ${m.numeroPlanilha ? `<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-pink-50 text-pink-700 border border-pink-200">#${m.numeroPlanilha}</span>` : ''}
              </div>
              <div class="text-xs text-slate-500 mt-1 flex items-center gap-1.5">
                <i data-lucide="store" class="w-3 h-3 text-pink-600"></i>
                <span>${est ? `${est.nome} (${est.cidade || ''})` : '<em class="text-amber-700 font-bold bg-amber-100 px-1.5 py-0.5 rounded">Sem Loja (Depósito)</em>'}</span>
              </div>
            </div>

            <!-- Status Badge -->
            <div>
              ${statusBadgeHtml}
            </div>
          </div>

          <!-- Offline details if any -->
          ${isOffline && offlineSince ? `
            <div class="mt-2.5 text-xs text-rose-800 bg-rose-50 px-3 py-1.5 rounded-xl border border-rose-200 font-medium">
              Offline desde: <strong class="font-bold">${offlineSince}</strong>
            </div>
          ` : ''}

          <!-- Integrator & External ID -->
          <div class="mt-3 grid grid-cols-2 gap-2 text-xs text-slate-600 bg-slate-50 p-2.5 rounded-xl border border-slate-200">
            <div>Plataforma: <strong class="text-slate-900 font-bold">${m.integracao || 'VendProMax'}</strong></div>
            <div>ID Externo: <strong class="text-slate-900 font-bold font-mono">${m.externalId || '--'}</strong></div>
          </div>

          <!-- Plush Stock Bar -->
          <div class="mt-3.5 space-y-1.5">
            <div class="flex justify-between text-xs">
              <span class="text-slate-600 font-medium flex items-center gap-1">
                <i data-lucide="package" class="w-3.5 h-3.5 text-pink-600"></i> Estoque Pelúcias:
              </span>
              <span class="font-extrabold ${isLowStock ? 'text-amber-700 font-black' : 'text-slate-900'}">
                ${atual} / ${cap} un (${perc}%)
              </span>
            </div>
            <div class="w-full bg-slate-100 h-2 rounded-full overflow-hidden border border-slate-200/60">
              <div class="${progressColor} h-full transition-all duration-500" style="width: ${perc}%"></div>
            </div>
          </div>
        </div>

        <!-- Action Buttons -->
        <div class="pt-3 border-t border-slate-100 flex items-center justify-between gap-2">
          <div class="flex items-center gap-2">
            <button onclick="openAbastecerModal(${m.id}, '${name}')" class="px-3 py-1.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold shadow-sm transition" title="Adicionar pelúcias">
              + Abastecer
            </button>
            <button onclick="openInventarioModal(${m.id}, '${name}', ${atual})" class="px-3 py-1.5 rounded-xl bg-white hover:bg-slate-100 border border-slate-300 text-slate-800 text-xs font-bold shadow-sm transition" title="Definir contagem exata">
              Balanço
            </button>
            <button onclick="openHistoricoModal(${m.id}, '${name}')" class="p-2 rounded-xl bg-white hover:bg-slate-100 border border-slate-300 text-slate-600 hover:text-slate-900 shadow-sm transition" title="Histórico de movimentações">
              <i data-lucide="history" class="w-3.5 h-3.5"></i>
            </button>
          </div>

          <div class="flex items-center gap-1.5">
            <button onclick="openEditMachineModal(${m.id})" class="p-2 rounded-xl bg-white hover:bg-slate-100 border border-slate-300 text-slate-600 hover:text-slate-900 shadow-sm transition" title="Editar máquina / Alocar loja">
              <i data-lucide="settings-2" class="w-3.5 h-3.5"></i>
            </button>
          </div>
        </div>
      </div>
    `;
  }).join('');
}

function filterMachines() {
  const search = (document.getElementById('machine-search-input')?.value || '').toLowerCase().trim();
  const status = document.getElementById('machine-status-filter')?.value || 'ALL';
  const activeFilter = document.getElementById('machine-active-filter')?.value || 'ALL';
  const storeFilter = document.getElementById('machine-store-filter')?.value || 'ALL';
  const integrator = document.getElementById('machine-integrator-filter')?.value || 'ALL';

  const offlineMap = new Map();
  if (state.monitorStatus?.maquinasOffline) {
    state.monitorStatus.maquinasOffline.forEach(m => offlineMap.set(m.nome.toLowerCase().trim(), m.offlineDesde));
  }

  const allMachines = state.machines || [];
  const filtered = allMachines.filter(m => {
    const name = (m.nomeIntegracao || m.descricao || '').toLowerCase();
    const estName = (m.estabelecimento?.nome || '').toLowerCase();
    const extId = (m.externalId || '').toLowerCase();
    const isActive = m.ativo !== false;
    const hasStore = !!m.estabelecimento;

    // Active filter
    if (activeFilter === 'ACTIVE' && !isActive) return false;
    if (activeFilter === 'INACTIVE' && isActive) return false;

    // Store filter
    if (storeFilter === 'WITH_STORE' && !hasStore) return false;
    if (storeFilter === 'WITHOUT_STORE' && hasStore) return false;

    // Text search
    if (search && !name.includes(search) && !estName.includes(search) && !extId.includes(search)) {
      return false;
    }

    // Integrator filter
    if (integrator !== 'ALL' && (m.integracao || '').toUpperCase() !== integrator) {
      return false;
    }

    // Offline check: only active machines with store can be offline
    let isOff = false;
    if (hasStore && isActive) {
      for (const offName of offlineMap.keys()) {
        if (name.includes(offName) || offName.includes(name) ||
            (estName && (estName.includes(offName) || offName.includes(estName)))) {
          isOff = true;
          break;
        }
      }
    }

    // Status filter
    if (status === 'ONLINE' && (!hasStore || !isActive || isOff)) return false;
    if (status === 'OFFLINE' && !isOff) return false;
    if (status === 'LOW_STOCK' && (m.estoqueAtual || 0) >= (m.estoqueMinimoAlerta || 30)) return false;

    return true;
  });

  const countBadge = document.getElementById('machines-count-info');
  if (countBadge) {
    countBadge.textContent = `${filtered.length} de ${allMachines.length} máq.`;
  }

  renderMachinesGrid(filtered, state.monitorStatus);
  if (window.lucide) lucide.createIcons();
}

async function syncMachines() {
  showToast('Consultando novas máquinas nas integradoras...', 'info');
  try {
    const res = await apiRequest('/api/maquinas/sync', { method: 'POST' });
    showToast(`Sincronização concluída! Total sincronizadas: ${res.length || 0}`, 'success');
    loadMaquinasData();
  } catch (err) {
    showToast('Erro ao sincronizar máquinas: ' + err.message, 'error');
  }
}

function openCreateMachineModal() {
  document.getElementById('modal-maquina-title').innerHTML = '<i data-lucide="cpu" class="w-4 h-4 text-purple-400"></i> Cadastrar Nova Máquina';
  document.getElementById('modal-maquina-id').value = '';
  document.getElementById('modal-maquina-nome').value = '';
  document.getElementById('modal-maquina-numero-planilha').value = '';
  document.getElementById('modal-maquina-integrador').value = 'VENDPROMAX';
  document.getElementById('modal-maquina-external-id').value = '';
  document.getElementById('modal-maquina-ativo').checked = true;

  const movData = document.getElementById('modal-maquina-mov-data');
  if (movData) movData.value = '';
  const movMotivo = document.getElementById('modal-maquina-mov-motivo');
  if (movMotivo) movMotivo.value = '';
  const btnHist = document.getElementById('btn-modal-maquina-hist');
  if (btnHist) btnHist.classList.add('hidden');

  populateEstabelecimentosSelect('modal-maquina-estab');
  openModal('modal-maquina');
}

async function fetchIntegratorMachineName() {
  const integrador = document.getElementById('modal-maquina-integrador').value;
  const extId = document.getElementById('modal-maquina-external-id').value.trim();

  if (!extId) {
    showToast('Digite o ID Externo antes de puxar os dados.', 'warning');
    return;
  }

  showToast(`Consultando ${integrador} para o ID ${extId}...`, 'info');
  try {
    const res = await apiRequest(`/api/maquinas/fetch-info?integracao=${encodeURIComponent(integrador)}&externalId=${encodeURIComponent(extId)}`);
    if (res && res.nome) {
      document.getElementById('modal-maquina-nome').value = res.nome;
      showToast(`Nome encontrado: "${res.nome}"`, 'success');
    } else {
      showToast('Nenhum nome retornado pela integradora para este ID.', 'warning');
    }
  } catch (err) {
    showToast('Erro ao consultar integradora: ' + err.message, 'error');
  }
}

function openEditMachineModal(id) {
  const m = state.machines.find(x => x.id === id);
  if (!m) return;

  document.getElementById('modal-maquina-title').innerHTML = '<i data-lucide="cpu" class="w-4 h-4 text-purple-400"></i> Editar Detalhes da Máquina';
  document.getElementById('modal-maquina-id').value = m.id;
  document.getElementById('modal-maquina-nome').value = m.nomeIntegracao || m.descricao || '';
  document.getElementById('modal-maquina-numero-planilha').value = m.numeroPlanilha || '';
  document.getElementById('modal-maquina-integrador').value = m.integracao || 'VENDPROMAX';
  document.getElementById('modal-maquina-external-id').value = m.externalId || '';
  document.getElementById('modal-maquina-ativo').checked = m.ativo !== false;

  const movData = document.getElementById('modal-maquina-mov-data');
  if (movData) movData.value = '';
  const movMotivo = document.getElementById('modal-maquina-mov-motivo');
  if (movMotivo) movMotivo.value = '';
  const btnHist = document.getElementById('btn-modal-maquina-hist');
  if (btnHist) btnHist.classList.remove('hidden');

  populateEstabelecimentosSelect('modal-maquina-estab', m.estabelecimento?.id);
  openModal('modal-maquina');
}

async function salvarMaquina() {
  const id = document.getElementById('modal-maquina-id').value;
  const nome = document.getElementById('modal-maquina-nome').value.trim();
  const num = document.getElementById('modal-maquina-numero-planilha').value;
  const integrador = document.getElementById('modal-maquina-integrador').value;
  const extId = document.getElementById('modal-maquina-external-id').value.trim();
  const estabId = document.getElementById('modal-maquina-estab').value;
  const ativo = document.getElementById('modal-maquina-ativo').checked;

  const movData = document.getElementById('modal-maquina-mov-data')?.value || '';
  const movMotivo = document.getElementById('modal-maquina-mov-motivo')?.value.trim() || '';

  if (!nome) {
    showToast('Informe o nome da máquina.', 'warning');
    return;
  }

  const payload = {
    nomeIntegracao: nome,
    descricao: nome,
    numeroPlanilha: num ? parseInt(num) : null,
    integracao: integrador,
    externalId: extId,
    ativo: ativo
  };

  let queryParams = '';
  if (movData) queryParams += `?dataHora=${encodeURIComponent(movData)}`;
  if (movMotivo) queryParams += `${queryParams ? '&' : '?'}motivo=${encodeURIComponent(movMotivo)}`;

  try {
    let saved;
    if (id) {
      saved = await apiRequest(`/api/maquinas/${id}`, { method: 'PUT', body: payload });
      // Atualizar alocação com histórico e vigência
      if (estabId) {
        await apiRequest(`/api/maquinas/${id}/alocar/${estabId}${queryParams}`, { method: 'PUT' });
      } else {
        await apiRequest(`/api/maquinas/${id}/desalocar${queryParams}`, { method: 'DELETE' });
      }
      showToast('Máquina atualizada com sucesso!', 'success');
    } else {
      saved = await apiRequest('/api/maquinas', { method: 'POST', body: payload });
      if (estabId && saved.id) {
        await apiRequest(`/api/maquinas/${saved.id}/alocar/${estabId}${queryParams}`, { method: 'PUT' });
      }
      showToast('Máquina cadastrada com sucesso!', 'success');
    }

    closeModal('modal-maquina');
    loadMaquinasData();
  } catch (err) {
    showToast('Erro ao salvar máquina: ' + err.message, 'error');
  }
}

// ============================================================================
// TAB 3: ESTOQUE DE PELÚCIAS
// ============================================================================

async function loadEstoqueData() {
  try {
    const data = await apiRequest('/api/estoque');
    state.stockData = data;

    const resumo = data.resumo || {};
    document.getElementById('estoque-total-pelucias').textContent = `${resumo.totalPeluciasEmEstoque || 0} un`;
    document.getElementById('estoque-media-maquina').textContent = `${resumo.mediaPeluciasPorMaquina || 0} un`;
    document.getElementById('estoque-maquinas-alerta').textContent = resumo.maquinasEmAlerta || 0;

    renderEstoqueTable(data.maquinas || []);
    if (window.lucide) lucide.createIcons();
  } catch (err) {
    showToast('Erro ao carregar estoque: ' + err.message, 'error');
  }
}

function renderEstoqueTable(items) {
  const tbody = document.getElementById('estoque-table-body');
  if (!tbody) return;

  if (items.length === 0) {
    tbody.innerHTML = `<tr><td colspan="7" class="p-8 text-center text-slate-500">Nenhuma máquina encontrada.</td></tr>`;
    return;
  }

  tbody.innerHTML = items.map(m => {
    const atual = m.estoqueAtual || 0;
    const cap = m.capacidadeMaxima || 80;
    const perc = m.percentualOcupacao || (cap > 0 ? Math.round((atual / cap) * 100) : 0);
    const isLow = m.estoqueBaixo;

    let barColor = 'bg-emerald-500';
    let statusBadge = '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">Normal</span>';

    if (isLow) {
      barColor = 'bg-rose-500';
      statusBadge = '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-rose-50 text-rose-700 border border-rose-200">Crítico (&lt; 30)</span>';
    } else if (perc <= 50) {
      barColor = 'bg-amber-500';
      statusBadge = '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-50 text-amber-700 border border-amber-200">Atenção</span>';
    }

    return `
      <tr class="hover:bg-slate-50 transition border-b border-slate-100">
        <td class="p-3.5">
          <div class="font-extrabold text-slate-900">${m.nome}</div>
          <div class="text-[11px] text-slate-500">${m.estabelecimentoNome ? `${m.estabelecimentoNome} (${m.cidade || ''})` : 'Sem Loja'}</div>
        </td>
        <td class="p-3.5 text-slate-600 font-mono text-[11px]">${m.integracao || '--'}</td>
        <td class="p-3.5 font-bold ${isLow ? 'text-rose-600' : 'text-slate-900'}">${atual} un</td>
        <td class="p-3.5 w-40">
          <div class="flex justify-between text-[11px] text-slate-500 mb-1 font-medium">
            <span>${perc}%</span>
            <span>Máx: ${cap}</span>
          </div>
          <div class="w-full bg-slate-100 h-2 rounded-full overflow-hidden border border-slate-200/60">
            <div class="${barColor} h-full" style="width: ${perc}%"></div>
          </div>
        </td>
        <td class="p-3.5">${statusBadge}</td>
        <td class="p-3.5 text-slate-600 text-[11px] font-medium">${m.ultimaSaida ? formatDate(m.ultimaSaida) : 'Nenhuma'}</td>
        <td class="p-3.5 text-right space-x-1">
          <button onclick="openAbastecerModal(${m.maquinaId}, '${m.nome}')" class="px-2.5 py-1 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold shadow-sm transition">
            + Abastecer
          </button>
          <button onclick="openInventarioModal(${m.maquinaId}, '${m.nome}', ${atual})" class="px-2.5 py-1 rounded-lg bg-white hover:bg-slate-100 border border-slate-300 text-slate-800 text-xs font-bold shadow-sm transition">
            Balanço
          </button>
          <button onclick="openLimitesModal(${m.maquinaId}, '${m.nome}', ${m.estoqueMinimoAlerta}, ${m.capacidadeMaxima})" class="p-1.5 rounded-lg bg-white hover:bg-slate-100 border border-slate-300 text-slate-600 hover:text-slate-900 shadow-sm transition" title="Configurar limites">
            <i data-lucide="sliders" class="w-3.5 h-3.5"></i>
          </button>
          <button onclick="openHistoricoModal(${m.maquinaId}, '${m.nome}')" class="p-1.5 rounded-lg bg-white hover:bg-slate-100 border border-slate-300 text-slate-600 hover:text-slate-900 shadow-sm transition" title="Histórico">
            <i data-lucide="history" class="w-3.5 h-3.5"></i>
          </button>
        </td>
      </tr>
    `;
  }).join('');
}

function filterEstoqueTable() {
  const search = document.getElementById('estoque-search-input').value.toLowerCase().trim();
  const onlyLow = document.getElementById('estoque-only-low-checkbox').checked;

  if (!state.stockData?.maquinas) return;

  const filtered = state.stockData.maquinas.filter(m => {
    const name = (m.nome || '').toLowerCase();
    const est = (m.estabelecimentoNome || '').toLowerCase();

    if (search && !name.includes(search) && !est.includes(search)) return false;
    if (onlyLow && !m.estoqueBaixo) return false;
    return true;
  });

  renderEstoqueTable(filtered);
  if (window.lucide) lucide.createIcons();
}

async function syncEstoqueVendProMax() {
  showToast('Sincronizando estoque com API VendProMax...', 'info');
  try {
    const res = await apiRequest('/api/estoque/sincronizar-vendpromax', { method: 'POST' });
    showToast(`Estoque VendProMax atualizado! ${res.totalAtualizadas || 0} máquinas.`, 'success');
    loadEstoqueData();
  } catch (err) {
    showToast('Erro ao sincronizar estoque: ' + err.message, 'error');
  }
}

// Modal Stock Handlers
function openAbastecerModal(id, nome) {
  document.getElementById('abastecer-maquina-id').value = id;
  document.getElementById('abastecer-maquina-nome').textContent = nome;
  document.getElementById('abastecer-quantidade').value = '20';
  document.getElementById('abastecer-responsavel').value = '';
  document.getElementById('abastecer-observacao').value = '';
  openModal('modal-abastecer');
}

async function confirmarAbastecimento() {
  const id = document.getElementById('abastecer-maquina-id').value;
  const qtd = parseInt(document.getElementById('abastecer-quantidade').value);
  const resp = document.getElementById('abastecer-responsavel').value.trim();
  const obs = document.getElementById('abastecer-observacao').value.trim();

  if (!qtd || qtd <= 0) {
    showToast('Informe uma quantidade válida.', 'warning');
    return;
  }

  try {
    await apiRequest(`/api/estoque/${id}/adicionar`, {
      method: 'POST',
      body: { quantidade: qtd, responsavel: resp, observacao: obs }
    });
    showToast(`+${qtd} pelúcias adicionadas com sucesso!`, 'success');
    closeModal('modal-abastecer');
    loadEstoqueData();
    if (state.activeTab === 'maquinas') loadMaquinasData();
  } catch (err) {
    showToast('Erro ao abastecer: ' + err.message, 'error');
  }
}

function openInventarioModal(id, nome, atual) {
  document.getElementById('inventario-maquina-id').value = id;
  document.getElementById('inventario-maquina-nome').textContent = nome;
  document.getElementById('inventario-novo-estoque').value = atual;
  document.getElementById('inventario-responsavel').value = '';
  document.getElementById('inventario-observacao').value = 'Contagem física / Balanço';
  openModal('modal-inventario');
}

async function confirmarInventario() {
  const id = document.getElementById('inventario-maquina-id').value;
  const novo = parseInt(document.getElementById('inventario-novo-estoque').value);
  const resp = document.getElementById('inventario-responsavel').value.trim();
  const obs = document.getElementById('inventario-observacao').value.trim();

  if (isNaN(novo) || novo < 0) {
    showToast('Informe uma quantidade válida.', 'warning');
    return;
  }

  try {
    await apiRequest(`/api/estoque/${id}/definir`, {
      method: 'PUT',
      body: { novoEstoque: novo, responsavel: resp, observacao: obs }
    });
    showToast(`Estoque ajustado para ${novo} pelúcias com sucesso!`, 'success');
    closeModal('modal-inventario');
    loadEstoqueData();
    if (state.activeTab === 'maquinas') loadMaquinasData();
  } catch (err) {
    showToast('Erro ao ajustar estoque: ' + err.message, 'error');
  }
}

function openLimitesModal(id, nome, min, max) {
  document.getElementById('limites-maquina-id').value = id;
  document.getElementById('limites-maquina-nome').textContent = nome;
  document.getElementById('limites-minimo').value = min || 30;
  document.getElementById('limites-maximo').value = max || 80;
  openModal('modal-limites-estoque');
}

async function confirmarLimitesEstoque() {
  const id = document.getElementById('limites-maquina-id').value;
  const min = parseInt(document.getElementById('limites-minimo').value);
  const max = parseInt(document.getElementById('limites-maximo').value);

  if (isNaN(min) || isNaN(max) || min < 1 || max < min) {
    showToast('Informe limites válidos (mínimo &lt; máximo).', 'warning');
    return;
  }

  try {
    await apiRequest(`/api/estoque/${id}/limite-alerta`, {
      method: 'PUT',
      body: { estoqueMinimoAlerta: min, capacidadeMaxima: max }
    });
    showToast('Limites de estoque configurados com sucesso!', 'success');
    closeModal('modal-limites-estoque');
    loadEstoqueData();
    if (state.activeTab === 'maquinas') loadMaquinasData();
  } catch (err) {
    showToast('Erro ao configurar limites: ' + err.message, 'error');
  }
}

async function openHistoricoModal(id, nome) {
  document.getElementById('historico-maquina-nome').textContent = nome;
  const list = document.getElementById('historico-estoque-lista');
  list.innerHTML = '<div class="text-center py-6 text-slate-500">Carregando histórico...</div>';
  openModal('modal-historico-estoque');

  try {
    const historico = await apiRequest(`/api/estoque/${id}/historico`);
    if (historico.length === 0) {
      list.innerHTML = '<div class="text-center py-6 text-slate-500">Nenhuma movimentação registrada para esta máquina.</div>';
      return;
    }

    list.innerHTML = historico.map(h => {
      let badge = '';
      if (h.tipo === 'ENTRADA') badge = '<span class="px-2 py-0.5 rounded text-[11px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">Entrada +' + h.quantidade + '</span>';
      else if (h.tipo === 'SAIDA_PREMIO') badge = '<span class="px-2 py-0.5 rounded text-[11px] font-bold bg-rose-50 text-rose-700 border border-rose-200">Prêmio -' + h.quantidade + '</span>';
      else badge = '<span class="px-2 py-0.5 rounded text-[11px] font-bold bg-purple-50 text-purple-700 border border-purple-200">Balanço (' + h.quantidade + ')</span>';

      return `
        <div class="p-3 rounded-xl bg-slate-50 border border-slate-200 flex items-center justify-between">
          <div class="space-y-1">
            <div class="flex items-center gap-2">
              ${badge}
              <span class="text-slate-800 font-semibold">${h.observacao || h.origem || 'Movimentação'}</span>
            </div>
            <div class="text-[11px] text-slate-500 font-medium">
              ${formatDate(h.dataMovimentacao)} ${h.responsavel ? 'por ' + h.responsavel : ''}
            </div>
          </div>
          <div class="text-right">
            <div class="font-extrabold text-slate-900">${h.estoqueNovo} un</div>
            <div class="text-[10px] text-slate-500 font-medium">era ${h.estoqueAnterior} un</div>
          </div>
        </div>
      `;
    }).join('');
  } catch (err) {
    list.innerHTML = `<div class="text-rose-400 text-center py-4">Erro ao carregar histórico: ${err.message}</div>`;
  }
}

// ============================================================================
// TAB 4: COLETA DE ESPÉCIE (CASH)
// ============================================================================

async function loadColetasData() {
  try {
    const statusFilter = document.getElementById('coletas-filter-status').value;
    let url = '/api/coletas';
    if (statusFilter) url += `?status=${statusFilter}`;

    const [coletas, pendentes] = await Promise.all([
      apiRequest(url),
      apiRequest('/api/coletas/pendentes')
    ]);

    state.coletas = coletas;

    // Highlights
    const totalPendente = pendentes.length;
    const valorEsperadoPendente = pendentes.reduce((acc, c) => acc + (c.valorEsperado || 0), 0);
    const conferidas = coletas.filter(c => c.status === 'CONFERIDO' || c.status === 'CONFERIDO_DIVERGENTE').length;

    document.getElementById('coletas-pendentes-total').textContent = totalPendente;
    document.getElementById('coletas-valor-esperado-pendente').textContent = formatMoney(valorEsperadoPendente);
    document.getElementById('coletas-conferidas-total').textContent = conferidas;

    renderColetasTable(coletas);
    if (window.lucide) lucide.createIcons();
  } catch (err) {
    showToast('Erro ao carregar coletas: ' + err.message, 'error');
  }
}

function renderColetasTable(items) {
  const tbody = document.getElementById('coletas-table-body');
  if (!tbody) return;

  if (items.length === 0) {
    tbody.innerHTML = `<tr><td colspan="8" class="p-8 text-center text-slate-500">Nenhuma coleta registrada com o filtro selecionado.</td></tr>`;
    return;
  }

  tbody.innerHTML = items.map(c => {
    let statusBadge = '';
    let difBadge = '--';
    const isCancelada = c.status === 'CANCELADA';

    if (c.status === 'PENDENTE_CONFERENCIA') {
      statusBadge = '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-50 text-amber-700 border border-amber-200">Pendente de Conferência</span>';
    } else if (c.status === 'CONFERIDO') {
      statusBadge = '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">Conferido (100% OK)</span>';
      difBadge = '<span class="text-emerald-700 font-bold font-mono">R$ 0,00</span>';
    } else if (c.status === 'CONFERIDO_DIVERGENTE') {
      const dif = c.diferenca || 0;
      if (dif > 0) {
        statusBadge = '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-sky-50 text-sky-700 border border-sky-200">Sobra no Malote</span>';
        difBadge = `<span class="text-sky-700 font-bold font-mono">+${formatMoney(dif)}</span>`;
      } else {
        statusBadge = '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-rose-50 text-rose-700 border border-rose-200">Falta no Malote</span>';
        difBadge = `<span class="text-rose-700 font-bold font-mono">${formatMoney(dif)}</span>`;
      }
    } else if (isCancelada) {
      statusBadge = '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-100 text-slate-500 border border-slate-300">Cancelada</span>';
      difBadge = '<span class="text-slate-400 font-mono text-xs">--</span>';
    }

    const rowClass = isCancelada ? 'bg-slate-50/60 opacity-65 hover:bg-slate-100/60 transition border-b border-slate-100' : 'hover:bg-slate-50 transition border-b border-slate-100';

    return `
      <tr class="${rowClass}">
        <td class="p-3.5">
          <div class="font-extrabold ${isCancelada ? 'text-slate-500 line-through' : 'text-slate-900'}">${escapeHtml(c.maquinaNome || '')}</div>
          <div class="text-[11px] text-slate-500">${escapeHtml(c.estabelecimentoNome || 'Sem Loja')}</div>
          ${c.observacoes ? `<div class="text-[10px] text-slate-400 italic truncate max-w-[220px]" title="${escapeHtml(c.observacoes)}">${escapeHtml(c.observacoes)}</div>` : ''}
        </td>
        <td class="p-3.5 text-slate-700 font-medium">${formatDate(c.dataHoraColeta)}</td>
        <td class="p-3.5 text-slate-500 text-[11px] font-medium">${c.dataHoraUltimaColeta ? formatDate(c.dataHoraUltimaColeta) : 'Primeira Coleta'}</td>
        <td class="p-3.5 font-bold ${isCancelada ? 'line-through text-slate-400' : 'text-amber-800'} font-mono">${formatMoney(c.valorEsperado || 0)}</td>
        <td class="p-3.5 font-bold text-slate-900 font-mono">
          ${isCancelada ? '<span class="text-slate-400 line-through">' + (c.valorReal != null ? formatMoney(c.valorReal) : 'R$ 0,00') + '</span>' : (c.valorReal != null ? formatMoney(c.valorReal) : '<em class="text-slate-400">Aguardando...</em>')}
        </td>
        <td class="p-3.5 font-mono">${difBadge}</td>
        <td class="p-3.5">${statusBadge}</td>
        <td class="p-3.5 text-right whitespace-nowrap space-x-1">
          ${c.status === 'PENDENTE_CONFERENCIA' ? `
            <button onclick="openConferirColetaModal(${c.id}, '${escapeHtml(c.maquinaNome)}', '${c.dataHoraColeta}', ${c.valorEsperado || 0})" class="px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-semibold text-xs shadow-xs active:scale-95 transition inline-flex items-center gap-1">
              <i data-lucide="check" class="w-3.5 h-3.5"></i> Conferir Malote
            </button>
            <button onclick="openCancelarColetaModal(${c.id}, '${escapeHtml(c.maquinaNome)}', '${c.dataHoraColeta}', ${c.valorEsperado || 0})" class="px-2.5 py-1.5 rounded-lg bg-rose-50 hover:bg-rose-100 border border-rose-200 text-rose-700 font-semibold text-xs transition inline-flex items-center gap-1" title="Cancelar esta coleta">
              <i data-lucide="ban" class="w-3.5 h-3.5"></i> Cancelar
            </button>
          ` : isCancelada ? `
            <button onclick="excluirColeta(${c.id})" class="p-1.5 rounded text-slate-400 hover:text-rose-600 transition" title="Excluir coleta permanentemente">
              <i data-lucide="trash-2" class="w-4 h-4"></i>
            </button>
          ` : `
            <button onclick="openCancelarColetaModal(${c.id}, '${escapeHtml(c.maquinaNome)}', '${c.dataHoraColeta}', ${c.valorEsperado || 0})" class="p-1.5 rounded text-slate-400 hover:text-rose-600 transition" title="Cancelar esta coleta">
              <i data-lucide="ban" class="w-4 h-4"></i>
            </button>
            <button onclick="excluirColeta(${c.id})" class="p-1.5 rounded text-slate-400 hover:text-rose-600 transition" title="Excluir coleta">
              <i data-lucide="trash-2" class="w-4 h-4"></i>
            </button>
          `}
        </td>
      </tr>
    `;
  }).join('');
}

function openNovaColetaModal() {
  populateMaquinasSelect('nova-coleta-maquina');
  // Set current datetime to ISO input
  const now = new Date();
  now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
  document.getElementById('nova-coleta-datahora').value = now.toISOString().slice(0, 16);
  document.getElementById('nova-coleta-valor-real').value = '';
  document.getElementById('nova-coleta-responsavel').value = '';
  document.getElementById('nova-coleta-obs').value = '';
  document.getElementById('previa-coleta-card').classList.add('hidden');

  openModal('modal-nova-coleta');
}

async function calcularPreviaColeta() {
  const maquinaId = document.getElementById('nova-coleta-maquina').value;
  const dataHora = document.getElementById('nova-coleta-datahora').value;
  const card = document.getElementById('previa-coleta-card');

  if (!maquinaId) {
    card.classList.add('hidden');
    return;
  }

  try {
    let url = `/api/coletas/previa?maquinaId=${maquinaId}`;
    if (dataHora) url += `&dataHoraColeta=${dataHora}`;

    const previa = await apiRequest(url);
    card.classList.remove('hidden');

    document.getElementById('previa-valor-esperado').textContent = formatMoney(previa.valorEsperado || 0);
    document.getElementById('previa-ultima-data').textContent = previa.dataHoraUltimaColeta ? formatDate(previa.dataHoraUltimaColeta) : 'Nenhuma (primeira coleta)';
    document.getElementById('previa-total-entradas').textContent = formatMoney(previa.totalEntradasEspecie || 0);
  } catch (err) {
    console.error('Erro na prévia:', err);
  }
}

async function confirmarNovaColeta() {
  const maquinaId = document.getElementById('nova-coleta-maquina').value;
  const dataHora = document.getElementById('nova-coleta-datahora').value;
  const valorReal = document.getElementById('nova-coleta-valor-real').value;
  const resp = document.getElementById('nova-coleta-responsavel').value.trim();
  const obs = document.getElementById('nova-coleta-obs').value.trim();

  if (!maquinaId) {
    showToast('Selecione uma máquina.', 'warning');
    return;
  }

  const payload = {
    maquinaId: parseInt(maquinaId),
    dataHoraColeta: dataHora ? dataHora : null,
    valorReal: valorReal ? parseFloat(valorReal) : null,
    responsavel: resp,
    observacoes: obs
  };

  try {
    await apiRequest('/api/coletas', { method: 'POST', body: payload });
    showToast('Coleta registrada com sucesso! Notificação enviada.', 'success');
    closeModal('modal-nova-coleta');
    loadColetasData();
    if (state.activeTab === 'dashboard') loadDashboardData();
  } catch (err) {
    showToast('Erro ao registrar coleta: ' + err.message, 'error');
  }
}

function openConferirColetaModal(id, maquina, data, esperado) {
  document.getElementById('conferir-coleta-id').value = id;
  document.getElementById('conferir-coleta-maquina').textContent = maquina;
  document.getElementById('conferir-coleta-data').textContent = formatDate(data);
  document.getElementById('conferir-coleta-esperado').textContent = formatMoney(esperado);
  document.getElementById('conferir-coleta-valor-real').value = '';
  document.getElementById('conferir-coleta-obs').value = '';
  document.getElementById('conferir-divergencia-valor').textContent = 'Digite o valor contado...';
  document.getElementById('conferir-divergencia-valor').className = 'text-slate-400 font-bold';

  openModal('modal-conferir-coleta');
}

function calcularDivergenciaModal() {
  const esperadoText = document.getElementById('conferir-coleta-esperado').textContent;
  const esperado = parseMoney(esperadoText);
  const real = parseFloat(document.getElementById('conferir-coleta-valor-real').value) || 0;
  const dif = real - esperado;

  const divEl = document.getElementById('conferir-divergencia-valor');
  if (Math.abs(dif) < 0.01) {
    divEl.textContent = '✓ 100% Exato (Bateu perfeitamente!)';
    divEl.className = 'text-emerald-400 font-bold';
  } else if (dif > 0) {
    divEl.textContent = `+ Sobra de ${formatMoney(dif)}`;
    divEl.className = 'text-sky-400 font-bold';
  } else {
    divEl.textContent = `- Falta de ${formatMoney(Math.abs(dif))}`;
    divEl.className = 'text-rose-400 font-bold';
  }
}

async function confirmarConferenciaColeta() {
  const id = document.getElementById('conferir-coleta-id').value;
  const valorReal = document.getElementById('conferir-coleta-valor-real').value;
  const obs = document.getElementById('conferir-coleta-obs').value.trim();

  if (!valorReal) {
    showToast('Informe o valor real contado.', 'warning');
    return;
  }

  try {
    await apiRequest(`/api/coletas/${id}/conferir`, {
      method: 'PUT',
      body: {
        valorReal: parseFloat(valorReal),
        observacoes: obs
      }
    });
    showToast('Malote conferido com sucesso! Recibo disparado no WhatsApp.', 'success');
    closeModal('modal-conferir-coleta');
    loadColetasData();
    if (state.activeTab === 'dashboard') loadDashboardData();
  } catch (err) {
    showToast('Erro ao conferir malote: ' + err.message, 'error');
  }
}

async function excluirColeta(id) {
  if (!confirm('Deseja realmente excluir este registro de coleta?')) return;
  try {
    await apiRequest(`/api/coletas/${id}`, { method: 'DELETE' });
    showToast('Registro de coleta excluído.', 'info');
    loadColetasData();
  } catch (err) {
    showToast('Erro ao excluir: ' + err.message, 'error');
  }
}

function openCancelarColetaModal(id, maquina, data, esperado) {
  document.getElementById('cancelar-coleta-id').value = id;
  document.getElementById('cancelar-coleta-maquina').textContent = maquina || '--';
  document.getElementById('cancelar-coleta-data').textContent = formatDate(data);
  document.getElementById('cancelar-coleta-esperado').textContent = formatMoney(esperado || 0);
  document.getElementById('cancelar-coleta-motivo').value = '';

  openModal('modal-cancelar-coleta');
}

async function confirmarCancelamentoColeta() {
  const id = document.getElementById('cancelar-coleta-id').value;
  const motivo = document.getElementById('cancelar-coleta-motivo').value.trim();

  if (!id) return;

  try {
    await apiRequest(`/api/coletas/${id}/cancelar`, {
      method: 'PUT',
      body: { motivo: motivo }
    });
    showToast('Coleta cancelada com sucesso. Notificação enviada.', 'success');
    closeModal('modal-cancelar-coleta');
    loadColetasData();
    if (state.activeTab === 'dashboard') loadDashboardData();
  } catch (err) {
    showToast('Erro ao cancelar coleta: ' + err.message, 'error');
  }
}

// ============================================================================
// TAB 5: ALUGUÉIS & REPASSES
// ============================================================================

function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function formatOnlyDate(dateStr) {
  if (!dateStr) return '--';
  if (typeof dateStr === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(dateStr)) {
    const [y, m, d] = dateStr.split('-');
    return `${d}/${m}/${y}`;
  }
  return formatDate(dateStr);
}

function formatMesAnoLabel(mesAnoStr) {
  if (!mesAnoStr) return '--';
  if (mesAnoStr === 'TODOS') return 'Histórico Geral (Todos os Meses)';
  const parts = mesAnoStr.split('-');
  if (parts.length < 2) return mesAnoStr;
  const meses = [
    'Janeiro', 'Fevereiro', 'Março', 'Abril', 'Maio', 'Junho',
    'Julho', 'Agosto', 'Setembro', 'Outubro', 'Novembro', 'Dezembro'
  ];
  const mIndex = parseInt(parts[1], 10) - 1;
  const mesNome = (mIndex >= 0 && mIndex < 12) ? meses[mIndex] : parts[1];
  return `${mesNome} / ${parts[0]}`;
}

async function loadAlugueisData(preserveFilters = false) {
  try {
    if (!state.alugueis || typeof state.alugueis !== 'object' || Array.isArray(state.alugueis)) {
      state.alugueis = {};
    }
    if (!state.alugueis.selectedStatus) state.alugueis.selectedStatus = 'TODOS';
    if (!state.alugueis.selectedCidade) state.alugueis.selectedCidade = 'TODAS';
    if (!state.alugueis.selectedMes) {
      const today = new Date();
      state.alugueis.selectedMes = today.toISOString().slice(0, 7);
    }

    // 1. Carrega lista de meses disponíveis
    try {
      const meses = await apiRequest('/api/alugueis/meses');
      if (Array.isArray(meses) && meses.length > 0) {
        state.alugueis.mesesDisponiveis = meses;
        populateAlugueisMesesSelect(meses);
      }
    } catch (e) {
      console.warn('Não foi possível carregar lista de meses de aluguel:', e);
    }

    // 2. Carrega lista de aluguéis do backend
    let url = '/api/alugueis';
    if (state.alugueis.selectedMes && state.alugueis.selectedMes !== 'TODOS') {
      url += `?mesAno=${encodeURIComponent(state.alugueis.selectedMes)}`;
    }
    const items = await apiRequest(url);
    state.alugueis.rawList = Array.isArray(items) ? items : [];

    // 3. Atualiza cidades no select
    populateAlugueisCidadesSelect(state.alugueis.rawList);

    // 4. Aplica filtros e renderiza
    applyAlugueisFilter();

  } catch (err) {
    showToast('Erro ao carregar aluguéis: ' + err.message, 'error');
  }
}

function populateAlugueisMesesSelect(meses) {
  const select = document.getElementById('alugueis-mes-select');
  if (!select) return;

  const currentVal = state.alugueis.selectedMes || select.value;
  select.innerHTML = '';

  // Opção Todos os Meses
  const optTodos = document.createElement('option');
  optTodos.value = 'TODOS';
  optTodos.textContent = 'Histórico Geral (Todos os Meses)';
  select.appendChild(optTodos);

  meses.forEach(m => {
    const opt = document.createElement('option');
    opt.value = m;
    opt.textContent = formatMesAnoLabel(m);
    select.appendChild(opt);
  });

  if (currentVal && (meses.includes(currentVal) || currentVal === 'TODOS')) {
    select.value = currentVal;
  } else if (meses.length > 0) {
    select.value = meses[0];
    state.alugueis.selectedMes = meses[0];
  }

  updateAluguelMesAtalhoVisual();
}

function populateAlugueisCidadesSelect(items) {
  const select = document.getElementById('alugueis-cidade-select');
  if (!select) return;

  const currentCidade = state.alugueis.selectedCidade || 'TODAS';
  const cidadesSet = new Set();
  items.forEach(a => {
    if (a.estabelecimento && a.estabelecimento.cidade) {
      cidadesSet.add(a.estabelecimento.cidade.trim());
    }
  });

  const cidades = Array.from(cidadesSet).sort();
  select.innerHTML = '<option value="TODAS">Todas as Cidades</option>';
  cidades.forEach(c => {
    const opt = document.createElement('option');
    opt.value = c;
    opt.textContent = c;
    select.appendChild(opt);
  });

  if (cidades.includes(currentCidade)) {
    select.value = currentCidade;
  } else {
    select.value = 'TODAS';
    state.alugueis.selectedCidade = 'TODAS';
  }
}

function onAluguelMesSelectChange() {
  const select = document.getElementById('alugueis-mes-select');
  if (!select) return;
  state.alugueis.selectedMes = select.value;
  updateAluguelMesAtalhoVisual();
  loadAlugueisData(true);
}

function setAluguelMesAtalho(tipo) {
  const today = new Date();
  const mesAtual = today.toISOString().slice(0, 7);

  const prevDate = new Date(today.getFullYear(), today.getMonth() - 1, 1);
  const mesAnterior = prevDate.toISOString().slice(0, 7);

  if (tipo === 'ATUAL') {
    state.alugueis.selectedMes = mesAtual;
  } else if (tipo === 'ANTERIOR') {
    state.alugueis.selectedMes = mesAnterior;
  } else if (tipo === 'TODOS') {
    state.alugueis.selectedMes = 'TODOS';
  }

  const select = document.getElementById('alugueis-mes-select');
  if (select) select.value = state.alugueis.selectedMes;

  updateAluguelMesAtalhoVisual();
  loadAlugueisData(true);
}

function updateAluguelMesAtalhoVisual() {
  const current = state.alugueis.selectedMes;
  const today = new Date();
  const mesAtual = today.toISOString().slice(0, 7);
  const prevDate = new Date(today.getFullYear(), today.getMonth() - 1, 1);
  const mesAnterior = prevDate.toISOString().slice(0, 7);

  const btnAtual = document.getElementById('btn-atalho-mes-atual');
  const btnAnterior = document.getElementById('btn-atalho-mes-anterior');
  const btnTodos = document.getElementById('btn-atalho-mes-todos');

  const activeClass = 'px-2.5 py-1 text-xs font-bold rounded-lg transition text-pink-600 bg-white shadow-xs';
  const inactiveClass = 'px-2.5 py-1 text-xs font-semibold rounded-lg transition text-slate-600 hover:text-slate-900';

  if (btnAtual) btnAtual.className = (current === mesAtual) ? activeClass : inactiveClass;
  if (btnAnterior) btnAnterior.className = (current === mesAnterior) ? activeClass : inactiveClass;
  if (btnTodos) btnTodos.className = (current === 'TODOS') ? activeClass : inactiveClass;
}

function setAluguelStatusFiltro(status) {
  state.alugueis.selectedStatus = status;

  // Atualiza visual dos pills
  const statusMap = {
    'TODOS': 'filtro-status-todos',
    'EM_ABERTO': 'filtro-status-em-aberto',
    'VENCIDO': 'filtro-status-vencido',
    'PAGO': 'filtro-status-pago'
  };

  Object.entries(statusMap).forEach(([st, btnId]) => {
    const btn = document.getElementById(btnId);
    if (!btn) return;
    if (st === status) {
      btn.className = 'aluguel-status-pill px-3 py-1 rounded-xl text-xs font-bold transition bg-slate-900 text-white shadow-xs';
    } else {
      btn.className = 'aluguel-status-pill px-3 py-1 rounded-xl text-xs font-bold transition bg-slate-100 text-slate-700 hover:bg-slate-200 border border-slate-200';
    }
  });

  applyAlugueisFilter();
}

function applyAlugueisFilter() {
  const raw = state.alugueis.rawList || [];
  const hojeStr = new Date().toISOString().slice(0, 10);

  // 1. Contagens e Totais Gerais para os Cards e Badges
  let totalPrevisto = 0;
  let totalPago = 0;
  let totalPendente = 0;
  let totalVencido = 0;
  let qtdTotal = raw.length;
  let qtdPago = 0;
  let qtdPendente = 0;
  let qtdVencido = 0;

  raw.forEach(a => {
    const val = a.valor || 0;
    totalPrevisto += val;

    const isVencido = !a.pago && a.dataVencimento && a.dataVencimento < hojeStr;

    if (a.pago) {
      totalPago += val;
      qtdPago++;
    } else {
      if (isVencido) {
        totalVencido += val;
        qtdVencido++;
      } else {
        totalPendente += val;
        qtdPendente++;
      }
    }
  });

  const qtdEmAberto = qtdPendente + qtdVencido;

  // Atualiza KPI Cards
  const elTotal = document.getElementById('alugueis-total-mes');
  const elSubTotal = document.getElementById('alugueis-sub-total');
  const elPago = document.getElementById('alugueis-total-pago');
  const elSubPago = document.getElementById('alugueis-sub-pago');
  const elPendente = document.getElementById('alugueis-total-pendente');
  const elSubPendente = document.getElementById('alugueis-sub-pendente');
  const elVencido = document.getElementById('alugueis-total-vencido');
  const elSubVencido = document.getElementById('alugueis-sub-vencido');
  const cardVencido = document.getElementById('alugueis-card-vencido');

  if (elTotal) elTotal.textContent = formatMoney(totalPrevisto);
  if (elSubTotal) elSubTotal.textContent = `${qtdTotal} lojas cadastradas`;

  const pctPago = totalPrevisto > 0 ? Math.round((totalPago / totalPrevisto) * 100) : 0;
  if (elPago) elPago.textContent = formatMoney(totalPago);
  if (elSubPago) elSubPago.textContent = `${qtdPago} pagas (${pctPago}% do total)`;

  if (elPendente) elPendente.textContent = formatMoney(totalPendente);
  if (elSubPendente) elSubPendente.textContent = `${qtdPendente} a vencer no prazo`;

  if (elVencido) elVencido.textContent = formatMoney(totalVencido);
  if (elSubVencido) elSubVencido.textContent = qtdVencido > 0 ? `${qtdVencido} em atraso!` : 'Nenhum atrasado';

  if (cardVencido) {
    if (qtdVencido > 0) {
      cardVencido.classList.add('bg-rose-50/50', 'border-rose-300');
    } else {
      cardVencido.classList.remove('bg-rose-50/50', 'border-rose-300');
    }
  }

  // Atualiza Badges dos botões de Status
  const bTodos = document.getElementById('badge-count-todos');
  const bAberto = document.getElementById('badge-count-aberto');
  const bVencido = document.getElementById('badge-count-vencido');
  const bPago = document.getElementById('badge-count-pago');

  if (bTodos) bTodos.textContent = `(${qtdTotal})`;
  if (bAberto) bAberto.textContent = `(${qtdEmAberto})`;
  if (bVencido) bVencido.textContent = `(${qtdVencido})`;
  if (bPago) bPago.textContent = `(${qtdPago})`;

  // 2. Filtra a lista para a Tabela
  const statusFilter = state.alugueis.selectedStatus || 'TODOS';
  const cidadeSelect = document.getElementById('alugueis-cidade-select');
  const cidadeFilter = cidadeSelect ? cidadeSelect.value : 'TODAS';
  const searchInput = document.getElementById('alugueis-search-input');
  const searchVal = searchInput ? searchInput.value.toLowerCase().trim() : '';

  const filtered = raw.filter(a => {
    const isVencido = !a.pago && a.dataVencimento && a.dataVencimento < hojeStr;

    // Filtro por status
    if (statusFilter === 'PAGO' && !a.pago) return false;
    if (statusFilter === 'EM_ABERTO' && a.pago) return false;
    if (statusFilter === 'VENCIDO' && (a.pago || !isVencido)) return false;

    // Filtro por cidade
    if (cidadeFilter !== 'TODAS') {
      const cid = a.estabelecimento?.cidade || '';
      if (cid.toLowerCase() !== cidadeFilter.toLowerCase()) return false;
    }

    // Busca textual
    if (searchVal) {
      const estNome = (a.estabelecimento?.nome || '').toLowerCase();
      const estCid = (a.estabelecimento?.cidade || '').toLowerCase();
      const estResp = (a.estabelecimento?.responsavel || '').toLowerCase();
      const obs = (a.comprovanteOuObs || '').toLowerCase();
      const meio = (a.meioPagamentoUtilizado || '').toLowerCase();
      const mes = (a.mesAno || '').toLowerCase();
      const match = estNome.includes(searchVal) ||
                    estCid.includes(searchVal) ||
                    estResp.includes(searchVal) ||
                    obs.includes(searchVal) ||
                    meio.includes(searchVal) ||
                    mes.includes(searchVal);
      if (!match) return false;
    }

    return true;
  });

  renderAlugueisTable(filtered);
  if (window.lucide) lucide.createIcons();
}

function renderAlugueisTable(items) {
  const tbody = document.getElementById('alugueis-table-body');
  if (!tbody) return;

  if (!items || items.length === 0) {
    tbody.innerHTML = `
      <tr>
        <td colspan="7" class="p-8 text-center text-slate-500">
          <div class="flex flex-col items-center justify-center gap-2">
            <i data-lucide="inbox" class="w-8 h-8 text-slate-400"></i>
            <div class="font-bold text-slate-700">Nenhum aluguel encontrado para os filtros selecionados.</div>
            <div class="text-xs text-slate-500">Altere o mês, a situação de pagamento ou clique em "Gerar Cobranças".</div>
          </div>
        </td>
      </tr>
    `;
    return;
  }

  const hojeStr = new Date().toISOString().slice(0, 10);
  const hojeTime = new Date().setHours(0,0,0,0);

  tbody.innerHTML = items.map(a => {
    const est = a.estabelecimento || {};
    const isDinamico = (a.tipoAluguel === 'DINAMICO') || (est.tipoAluguel === 'DINAMICO');
    const isVencido = !a.pago && a.dataVencimento && a.dataVencimento < hojeStr;

    // Cálculo de dias de atraso ou dias restantes
    let prazoHtml = '';
    if (a.pago) {
      prazoHtml = `
        <span class="px-2 py-0.5 rounded-md text-[11px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200 inline-flex items-center gap-1">
          <i data-lucide="check" class="w-3 h-3"></i> Quitado
        </span>
        <div class="text-[10px] text-slate-500 mt-0.5">Venc: ${formatOnlyDate(a.dataVencimento)}</div>
      `;
    } else if (isVencido) {
      let diasAtraso = a.diasAtraso;
      if (!diasAtraso && a.dataVencimento) {
        const vencTime = new Date(a.dataVencimento + 'T00:00:00').getTime();
        diasAtraso = Math.max(1, Math.round((hojeTime - vencTime) / (1000 * 60 * 60 * 24)));
      }
      prazoHtml = `
        <span class="px-2 py-0.5 rounded-md text-[11px] font-bold bg-rose-100 text-rose-800 border border-rose-300 inline-flex items-center gap-1 shadow-xs animate-pulse">
          <i data-lucide="alert-triangle" class="w-3 h-3 text-rose-600"></i> Atrasado (${diasAtraso}d)
        </span>
        <div class="text-[10px] text-rose-700 font-bold mt-0.5">Venceu em: ${formatOnlyDate(a.dataVencimento)}</div>
      `;
    } else if (a.dataVencimento === hojeStr) {
      prazoHtml = `
        <span class="px-2 py-0.5 rounded-md text-[11px] font-bold bg-amber-100 text-amber-900 border border-amber-300 inline-flex items-center gap-1">
          <i data-lucide="clock" class="w-3 h-3 text-amber-700"></i> Vence Hoje!
        </span>
        <div class="text-[10px] text-amber-700 font-bold mt-0.5">Data: ${formatOnlyDate(a.dataVencimento)}</div>
      `;
    } else {
      let diasRest = 0;
      if (a.dataVencimento) {
        const vencTime = new Date(a.dataVencimento + 'T00:00:00').getTime();
        diasRest = Math.max(0, Math.round((vencTime - hojeTime) / (1000 * 60 * 60 * 24)));
      }
      prazoHtml = `
        <span class="px-2 py-0.5 rounded-md text-[11px] font-medium bg-slate-100 text-slate-700 border border-slate-200 inline-flex items-center gap-1">
          <i data-lucide="calendar" class="w-3 h-3 text-slate-500"></i> No Prazo (${diasRest}d)
        </span>
        <div class="text-[10px] text-slate-500 mt-0.5">Venc: ${formatOnlyDate(a.dataVencimento)}</div>
      `;
    }

    // Regra de Repasse HTML
    let regraHtml = '';
    if (isDinamico) {
      regraHtml = `
        <div class="font-bold text-pink-700 flex items-center gap-1 text-[11px]">
          <i data-lucide="calculator" class="w-3.5 h-3.5 text-pink-600"></i> Dinâmico (${a.percentualRepasse || est.percentualRepasse || 25}%)
        </div>
        <div class="text-[10px] text-slate-500 mt-0.5">
          Lucro Ref ${a.mesCompetencia || '--'}: <span class="font-mono font-bold text-slate-700">${formatMoney(a.lucroCalculado || 0)}</span>
        </div>
        ${a.valorManual ? '<span class="text-[9px] font-bold text-purple-700 bg-purple-50 px-1 py-0.2 rounded border border-purple-200 mt-0.5 inline-block">Valor Fixado</span>' : ''}
      `;
    } else {
      regraHtml = `
        <div class="font-bold text-slate-700 text-[11px]">Fixo Mensal</div>
        <div class="text-[10px] text-slate-500 mt-0.5">Acordo: <span class="font-mono font-semibold text-slate-700">${formatMoney(est.valorAcordado || a.valor || 0)}</span></div>
        ${a.valorManual ? '<span class="text-[9px] font-bold text-purple-700 bg-purple-50 px-1 py-0.2 rounded border border-purple-200 mt-0.5 inline-block">Ajuste Manual</span>' : ''}
      `;
    }

    // Status do Pagamento HTML
    let statusPagtoHtml = '';
    if (a.pago) {
      statusPagtoHtml = `
        <div class="text-xs font-bold text-emerald-800 flex items-center gap-1">
          <i data-lucide="check-circle-2" class="w-3.5 h-3.5 text-emerald-600"></i> Quitado em ${formatOnlyDate(a.dataPagamento)}
        </div>
        <div class="text-[11px] text-slate-600 font-medium mt-0.5">
          Meio: <span class="font-bold uppercase text-slate-800">${a.meioPagamentoUtilizado || 'PIX'}</span>
        </div>
        ${a.comprovanteOuObs ? `<div class="text-[10px] text-slate-500 italic max-w-xs truncate mt-0.5" title="${escapeHtml(a.comprovanteOuObs)}">${escapeHtml(a.comprovanteOuObs)}</div>` : ''}
      `;
    } else {
      statusPagtoHtml = `
        <div class="text-xs font-bold ${isVencido ? 'text-rose-700' : 'text-amber-700'} flex items-center gap-1">
          <i data-lucide="${isVencido ? 'alert-triangle' : 'clock'}" class="w-3.5 h-3.5"></i> ${isVencido ? 'Aguardando Pagamento (Atrasado)' : 'Aguardando Pagamento'}
        </div>
        ${a.comprovanteOuObs ? `<div class="text-[10px] text-slate-500 italic max-w-xs truncate mt-0.5" title="${escapeHtml(a.comprovanteOuObs)}">Obs: ${escapeHtml(a.comprovanteOuObs)}</div>` : ''}
      `;
    }

    const rowBg = isVencido ? 'bg-rose-50/20 hover:bg-rose-50/40' : 'hover:bg-slate-50/80';

    return `
      <tr class="${rowBg} transition border-b border-slate-100">
        <!-- Coluna 1: Ponto Comercial & Local -->
        <td class="p-3.5">
          <div class="font-black text-slate-900 text-xs sm:text-sm hover:text-pink-600 transition cursor-pointer flex items-center gap-1.5" onclick="openHistoricoAluguelModal(${est.id}, '${escapeHtml(est.nome)}')" title="Clique para ver o histórico completo de pagamentos desta loja">
            <span>${escapeHtml(est.nome || 'Ponto Comercial')}</span>
            <i data-lucide="history" class="w-3.5 h-3.5 text-slate-400 hover:text-pink-600"></i>
          </div>
          <div class="text-[11px] text-slate-500 font-medium flex items-center gap-2 mt-0.5 flex-wrap">
            <span class="font-bold text-slate-700">${escapeHtml(est.cidade || '--')}</span>
            ${est.responsavel ? `<span>• ${escapeHtml(est.responsavel)}</span>` : ''}
          </div>
        </td>

        <!-- Coluna 2: Mês Ref. -->
        <td class="p-3.5">
          <span class="font-mono font-bold text-slate-800 bg-slate-100 px-2 py-1 rounded-lg text-xs">
            ${a.mesAno || '--'}
          </span>
        </td>

        <!-- Coluna 3: Vencimento & Prazo -->
        <td class="p-3.5">
          ${prazoHtml}
        </td>

        <!-- Coluna 4: Tipo de Repasse -->
        <td class="p-3.5">
          ${regraHtml}
        </td>

        <!-- Coluna 5: Valor do Aluguel -->
        <td class="p-3.5">
          <div class="font-mono font-black text-slate-900 text-sm sm:text-base">
            ${formatMoney(a.valor || 0)}
          </div>
        </td>

        <!-- Coluna 6: Situação / Pagamento -->
        <td class="p-3.5">
          ${statusPagtoHtml}
        </td>

        <!-- Coluna 7: Ações Rápidas -->
        <td class="p-3.5 text-right space-x-1 whitespace-nowrap">
          ${!a.pago ? `
            <button onclick="openPagarAluguelModal(${a.id})" class="px-3 py-1.5 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs shadow-xs active:scale-95 transition inline-flex items-center gap-1" title="Registrar quitação deste aluguel">
              <i data-lucide="check" class="w-3.5 h-3.5"></i> Pagar
            </button>
          ` : `
            <button onclick="estornarAluguel(${a.id})" class="px-2.5 py-1.5 rounded-xl bg-rose-50 hover:bg-rose-100 border border-rose-200 text-rose-700 text-xs font-bold transition inline-flex items-center gap-1" title="Estornar pagamento para pendente">
              <i data-lucide="undo-2" class="w-3.5 h-3.5"></i> Estornar
            </button>
          `}

          <button onclick="openDefinirValorAluguelModal(${a.id}, '${escapeHtml(est.nome)}', ${a.valor || 0})" class="px-2.5 py-1.5 rounded-xl bg-white hover:bg-slate-100 border border-slate-300 text-slate-700 text-xs font-bold shadow-xs transition" title="Ajustar valor acordado para este mês">
            Ajustar
          </button>

          ${isDinamico ? `
            <button onclick="calcularDinamicoIndividual(${a.id})" class="px-2 py-1.5 rounded-xl bg-pink-50 hover:bg-pink-100 border border-pink-200 text-pink-700 text-xs font-bold transition" title="Recalcular repasse dinâmico (25% do lucro)">
              <i data-lucide="calculator" class="w-3.5 h-3.5"></i>
            </button>
          ` : ''}

          <button onclick="openPixModal(${est.id}, '${escapeHtml(est.nome)}')" class="p-1.5 rounded-xl bg-white hover:bg-slate-100 border border-slate-300 text-slate-600 hover:text-emerald-600 shadow-xs transition" title="Ver chaves PIX da loja">
            <i data-lucide="qr-code" class="w-3.5 h-3.5"></i>
          </button>

          <button onclick="openHistoricoAluguelModal(${est.id}, '${escapeHtml(est.nome)}')" class="p-1.5 rounded-xl bg-white hover:bg-slate-100 border border-slate-300 text-slate-600 hover:text-purple-600 shadow-xs transition" title="Histórico de pagamentos desta loja">
            <i data-lucide="history" class="w-3.5 h-3.5"></i>
          </button>
        </td>
      </tr>
    `;
  }).join('');
}

async function openPagarAluguelModal(aluguelId) {
  const item = (state.alugueis.rawList || []).find(a => a.id === aluguelId);
  const est = item?.estabelecimento || {};

  document.getElementById('pagar-aluguel-id').value = aluguelId;
  document.getElementById('pagar-aluguel-loja').textContent = est.nome || '--';
  document.getElementById('pagar-aluguel-mes').textContent = item?.mesAno || '--';
  document.getElementById('pagar-aluguel-valor').textContent = formatMoney(item?.valor || 0);

  const today = new Date().toISOString().slice(0, 10);
  document.getElementById('pagar-aluguel-data').value = today;
  document.getElementById('pagar-aluguel-meio').value = 'PIX';
  document.getElementById('pagar-aluguel-obs').value = '';

  // Carrega chave PIX cadastrada da loja para facilitar a transferência
  const pixBox = document.getElementById('pagar-aluguel-pix-box');
  const pixChaveEl = document.getElementById('pagar-aluguel-pix-chave');
  const btnCopiar = document.getElementById('btn-copiar-pix-modal');

  state.alugueis.currentModalPixKey = null;

  if (pixChaveEl) pixChaveEl.textContent = 'Buscando chave PIX da loja...';
  if (btnCopiar) btnCopiar.style.display = 'none';

  openModal('modal-pagar-aluguel');

  if (est.id) {
    try {
      const keys = await apiRequest(`/api/estabelecimentos/${est.id}/meios-pagamento`);
      const pixKey = Array.isArray(keys) ? (keys.find(k => k.tipo === 'PIX' || k.principal) || keys[0]) : null;
      if (pixKey && pixKey.chaveOuDetalhe) {
        state.alugueis.currentModalPixKey = pixKey.chaveOuDetalhe;
        if (pixChaveEl) pixChaveEl.textContent = pixKey.chaveOuDetalhe;
        if (btnCopiar) btnCopiar.style.display = 'inline-block';
      } else {
        if (pixChaveEl) pixChaveEl.textContent = 'Nenhuma chave PIX cadastrada para este ponto comercial.';
      }
    } catch (e) {
      if (pixChaveEl) pixChaveEl.textContent = 'Não foi possível carregar a chave PIX.';
    }
  }
}

function copiarPixDoModalPagamento() {
  const key = state.alugueis.currentModalPixKey;
  if (!key) {
    showToast('Nenhuma chave PIX para copiar.', 'warning');
    return;
  }
  navigator.clipboard.writeText(key).then(() => {
    showToast('Chave PIX copiada para a área de transferência!', 'success');
  }).catch(() => {
    showToast('Não foi possível copiar automaticamente.', 'warning');
  });
}

async function confirmarPagamentoAluguel() {
  const id = document.getElementById('pagar-aluguel-id').value;
  const data = document.getElementById('pagar-aluguel-data').value;
  const meio = document.getElementById('pagar-aluguel-meio').value;
  const obs = document.getElementById('pagar-aluguel-obs').value.trim();

  try {
    await apiRequest(`/api/alugueis/${id}/pagar`, {
      method: 'PUT',
      body: { dataPagamento: data, meio: meio, obs: obs }
    });
    showToast('Pagamento de aluguel registrado com sucesso!', 'success');
    closeModal('modal-pagar-aluguel');
    loadAlugueisData(true);
  } catch (err) {
    showToast('Erro ao registrar pagamento: ' + err.message, 'error');
  }
}

async function estornarAluguel(id) {
  if (!confirm('Deseja realmente estornar o pagamento deste aluguel e colocá-lo como em aberto?')) return;
  try {
    await apiRequest(`/api/alugueis/${id}/estornar`, { method: 'PUT' });
    showToast('Pagamento estornado com sucesso! Aluguel voltou para em aberto.', 'info');
    loadAlugueisData(true);
  } catch (err) {
    showToast('Erro ao estornar: ' + err.message, 'error');
  }
}

function openDefinirValorAluguelModal(id, loja, valorAtual) {
  document.getElementById('valor-aluguel-id').value = id;
  document.getElementById('valor-aluguel-loja').textContent = loja;
  document.getElementById('valor-aluguel-novo').value = valorAtual || 0;
  document.getElementById('valor-aluguel-obs').value = '';
  openModal('modal-valor-aluguel');
}

async function confirmarDefinirValorAluguel() {
  const id = document.getElementById('valor-aluguel-id').value;
  const valor = parseFloat(document.getElementById('valor-aluguel-novo').value);
  const obs = document.getElementById('valor-aluguel-obs').value.trim();

  if (isNaN(valor) || valor < 0) {
    showToast('Informe um valor válido maior ou igual a zero.', 'warning');
    return;
  }

  try {
    let url = `/api/alugueis/${id}/valor?valor=${valor}`;
    if (obs) url += `&obs=${encodeURIComponent(obs)}`;
    await apiRequest(url, { method: 'PUT' });
    showToast('Valor do aluguel atualizado com sucesso!', 'success');
    closeModal('modal-valor-aluguel');
    loadAlugueisData(true);
  } catch (err) {
    showToast('Erro ao definir valor: ' + err.message, 'error');
  }
}

async function openHistoricoAluguelModal(estabelecimentoId, nomeLoja) {
  const sub = document.getElementById('historico-aluguel-loja-sub');
  const tbody = document.getElementById('historico-aluguel-tbody');
  if (sub) sub.textContent = `Ponto Comercial: ${nomeLoja}`;
  if (tbody) tbody.innerHTML = '<tr><td colspan="6" class="p-6 text-center text-slate-500">Carregando histórico do banco de dados...</td></tr>';

  openModal('modal-historico-aluguel');

  try {
    const list = await apiRequest(`/api/alugueis/estabelecimento/${estabelecimentoId}`);
    if (!list || list.length === 0) {
      if (tbody) tbody.innerHTML = '<tr><td colspan="6" class="p-6 text-center text-slate-500">Nenhum registro de aluguel encontrado para esta loja.</td></tr>';
      return;
    }

    const hojeStr = new Date().toISOString().slice(0, 10);

    tbody.innerHTML = list.map(a => {
      const isVencido = !a.pago && a.dataVencimento && a.dataVencimento < hojeStr;
      const statusBadge = a.pago
        ? '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">Pago</span>'
        : (isVencido
            ? '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-rose-50 text-rose-700 border border-rose-200">Atrasado</span>'
            : '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-50 text-amber-700 border border-amber-200">Em Aberto</span>');

      return `
        <tr class="hover:bg-slate-50 transition border-b border-slate-100">
          <td class="p-2.5 font-mono font-bold text-slate-900">${a.mesAno}</td>
          <td class="p-2.5 text-slate-600">${formatOnlyDate(a.dataVencimento)}</td>
          <td class="p-2.5 text-slate-600 font-medium">${a.tipoAluguel || 'FIXO'}</td>
          <td class="p-2.5 font-mono font-black text-slate-900">${formatMoney(a.valor || 0)}</td>
          <td class="p-2.5">${statusBadge}</td>
          <td class="p-2.5 text-slate-600 text-[11px]">
            ${a.pago ? `Pago em ${formatOnlyDate(a.dataPagamento)} (${a.meioPagamentoUtilizado || 'PIX'})` : '--'}
            ${a.comprovanteOuObs ? `<div class="italic text-[10px] text-slate-500">${escapeHtml(a.comprovanteOuObs)}</div>` : ''}
          </td>
        </tr>
      `;
    }).join('');

    if (window.lucide) lucide.createIcons();

  } catch (err) {
    if (tbody) tbody.innerHTML = `<tr><td colspan="6" class="p-6 text-center text-rose-500">Erro ao carregar histórico: ${err.message}</td></tr>`;
  }
}

async function gerarCobrancasMes() {
  let mes = state.alugueis.selectedMes;
  if (!mes || mes === 'TODOS') {
    mes = new Date().toISOString().slice(0, 7);
  }
  showToast(`Gerando cobranças para o mês ${mes}...`, 'info');
  try {
    const gerados = await apiRequest(`/api/alugueis/gerar-mes?mesAno=${encodeURIComponent(mes)}`, { method: 'POST' });
    showToast(`Cobranças geradas! Total de ${gerados.length || 0} lojas processadas.`, 'success');
    loadAlugueisData(true);
  } catch (err) {
    showToast('Erro ao gerar cobranças: ' + err.message, 'error');
  }
}

async function recalcularDinamicosMes() {
  let mes = state.alugueis.selectedMes;
  if (!mes || mes === 'TODOS') {
    mes = new Date().toISOString().slice(0, 7);
  }
  showToast(`Recalculando aluguéis dinâmicos (25% do lucro) para ${mes}...`, 'info');
  try {
    const atualizados = await apiRequest(`/api/alugueis/calcular-dinamicos?mesAno=${encodeURIComponent(mes)}`, { method: 'POST' });
    showToast(`Recalculados com sucesso! ${atualizados.length || 0} lojas dinâmicas atualizadas.`, 'success');
    loadAlugueisData(true);
  } catch (err) {
    showToast('Erro ao recalcular: ' + err.message, 'error');
  }
}

async function calcularDinamicoIndividual(id) {
  showToast('Calculando repasse dinâmico (25% do lucro)...', 'info');
  try {
    await apiRequest(`/api/alugueis/${id}/calcular-dinamico`, { method: 'POST' });
    showToast('Repasse dinâmico recalculado com sucesso!', 'success');
    loadAlugueisData(true);
  } catch (err) {
    showToast('Erro ao calcular: ' + err.message, 'error');
  }
}


// ============================================================================
// TAB 6: PONTOS COMERCIAIS (ESTABELECIMENTOS)
// ============================================================================

async function loadEstabelecimentosData() {
  try {
    const stores = await apiRequest('/api/estabelecimentos');
    state.stores = stores;
    renderEstabelecimentosGrid(stores);
    if (window.lucide) lucide.createIcons();
  } catch (err) {
    showToast('Erro ao carregar estabelecimentos: ' + err.message, 'error');
  }
}

function renderEstabelecimentosGrid(stores) {
  const grid = document.getElementById('estabelecimentos-grid');
  if (!grid) return;

  if (stores.length === 0) {
    grid.innerHTML = `<div class="col-span-full text-center py-12 text-slate-500">Nenhum estabelecimento cadastrado.</div>`;
    return;
  }

  grid.innerHTML = stores.map(e => {
    const isDinamico = e.tipoAluguel === 'DINAMICO';
    return `
      <div class="glass-card p-5 flex flex-col justify-between space-y-4">
        <div>
          <div class="flex items-start justify-between">
            <div>
              <div class="flex items-center gap-2">
                <span class="font-extrabold text-base text-slate-900">${e.nome}</span>
                ${e.ativo ? '' : '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-rose-50 text-rose-700 border border-rose-200">Inativo</span>'}
              </div>
              <div class="text-xs text-slate-500 mt-1 flex items-center gap-1.5 font-medium">
                <i data-lucide="map-pin" class="w-3.5 h-3.5 text-pink-600"></i>
                <span>${e.cidade || 'Cidade não informada'}</span>
              </div>
            </div>

            <span class="px-2.5 py-1 rounded-full text-[11px] font-bold ${isDinamico ? 'bg-purple-50 text-purple-700 border border-purple-200' : 'bg-slate-100 text-slate-700 border border-slate-200'}">
              ${isDinamico ? `Dinâmico (${e.percentualRepasse || 25}%)` : `Fixo: ${formatMoney(e.valorAcordado || 0)}`}
            </span>
          </div>

          <div class="mt-4 grid grid-cols-2 gap-2 text-xs text-slate-600 bg-slate-50 p-2.5 rounded-xl border border-slate-200">
            <div>Contato: <strong class="text-slate-900 font-bold block">${e.responsavel || '--'}</strong></div>
            <div>Vencimento: <strong class="text-slate-900 font-bold block">Todo dia ${e.diaPagamento || 10}</strong></div>
          </div>
        </div>

        <div class="pt-3 border-t border-slate-100 flex items-center justify-between">
          <button onclick="openPixModal(${e.id}, '${e.nome}')" class="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-emerald-50 hover:bg-emerald-100 text-emerald-800 border border-emerald-200 text-xs font-bold transition shadow-sm">
            <i data-lucide="qr-code" class="w-3.5 h-3.5 text-emerald-600"></i> Chaves PIX
          </button>

          <div class="flex items-center gap-1.5">
            <button onclick="openEditEstabelecimentoModal(${e.id})" class="p-2 rounded-xl bg-white hover:bg-slate-100 border border-slate-300 text-slate-600 hover:text-slate-900 transition shadow-sm" title="Editar">
              <i data-lucide="edit-2" class="w-3.5 h-3.5"></i>
            </button>
            <button onclick="toggleEstabelecimentoAtivo(${e.id}, ${e.ativo})" class="p-2 rounded-xl bg-white hover:bg-slate-100 border border-slate-300 ${e.ativo ? 'text-amber-600' : 'text-emerald-600'} transition shadow-sm" title="${e.ativo ? 'Desativar ponto' : 'Reativar ponto'}">
              <i data-lucide="${e.ativo ? 'pause' : 'play'}" class="w-3.5 h-3.5"></i>
            </button>
          </div>
        </div>
      </div>
    `;
  }).join('');
}

function filterEstabelecimentos() {
  const search = document.getElementById('estab-search-input').value.toLowerCase().trim();
  const filtered = state.stores.filter(e => {
    const nome = (e.nome || '').toLowerCase();
    const cid = (e.cidade || '').toLowerCase();
    return nome.includes(search) || cid.includes(search);
  });
  renderEstabelecimentosGrid(filtered);
  if (window.lucide) lucide.createIcons();
}

function openCreateEstabelecimentoModal() {
  document.getElementById('modal-estab-title').innerHTML = '<i data-lucide="store" class="w-4 h-4 text-purple-400"></i> Cadastrar Novo Ponto Comercial';
  document.getElementById('estab-id').value = '';
  document.getElementById('estab-nome').value = '';
  document.getElementById('estab-cidade').value = '';
  document.getElementById('estab-responsavel').value = '';
  document.getElementById('estab-tipo-aluguel').value = 'FIXO';
  document.getElementById('estab-dia-vencimento').value = 10;
  document.getElementById('estab-valor-acordado').value = 250;
  document.getElementById('estab-percentual-repasse').value = 25;
  document.getElementById('estab-ativo').checked = true;

  openModal('modal-estabelecimento');
}

function openEditEstabelecimentoModal(id) {
  const est = state.stores.find(x => x.id === id);
  if (!est) return;

  document.getElementById('modal-estab-title').innerHTML = '<i data-lucide="store" class="w-4 h-4 text-purple-400"></i> Editar Ponto Comercial';
  document.getElementById('estab-id').value = est.id;
  document.getElementById('estab-nome').value = est.nome || '';
  document.getElementById('estab-cidade').value = est.cidade || '';
  document.getElementById('estab-responsavel').value = est.responsavel || '';
  document.getElementById('estab-tipo-aluguel').value = est.tipoAluguel || 'FIXO';
  document.getElementById('estab-dia-vencimento').value = est.diaPagamento || 10;
  document.getElementById('estab-valor-acordado').value = est.valorAcordado || 0;
  document.getElementById('estab-percentual-repasse').value = est.percentualRepasse || 25;
  document.getElementById('estab-ativo').checked = est.ativo !== false;

  openModal('modal-estabelecimento');
}

async function salvarEstabelecimento() {
  const id = document.getElementById('estab-id').value;
  const nome = document.getElementById('estab-nome').value.trim();
  const cidade = document.getElementById('estab-cidade').value.trim();
  const resp = document.getElementById('estab-responsavel').value.trim();
  const tipo = document.getElementById('estab-tipo-aluguel').value;
  const dia = parseInt(document.getElementById('estab-dia-vencimento').value) || 10;
  const valor = parseFloat(document.getElementById('estab-valor-acordado').value) || 0;
  const perc = parseFloat(document.getElementById('estab-percentual-repasse').value) || 25;
  const ativo = document.getElementById('estab-ativo').checked;

  if (!nome) {
    showToast('Informe o nome do estabelecimento.', 'warning');
    return;
  }

  const payload = {
    nome,
    cidade,
    responsavel: resp,
    tipoAluguel: tipo,
    diaPagamento: dia,
    valorAcordado: valor,
    percentualRepasse: perc,
    ativo
  };

  try {
    if (id) {
      await apiRequest(`/api/estabelecimentos/${id}`, { method: 'PUT', body: payload });
      showToast('Ponto comercial atualizado!', 'success');
    } else {
      await apiRequest('/api/estabelecimentos', { method: 'POST', body: payload });
      showToast('Ponto comercial cadastrado!', 'success');
    }
    closeModal('modal-estabelecimento');
    loadEstabelecimentosData();
    loadInitialLookups();
  } catch (err) {
    showToast('Erro ao salvar ponto: ' + err.message, 'error');
  }
}

async function toggleEstabelecimentoAtivo(id, atual) {
  const action = atual ? 'desativar' : 'ativar';
  try {
    await apiRequest(`/api/estabelecimentos/${id}/${action}`, { method: 'PUT' });
    showToast(`Estabelecimento ${atual ? 'desativado' : 'reativado'}!`, 'info');
    loadEstabelecimentosData();
  } catch (err) {
    showToast('Erro: ' + err.message, 'error');
  }
}

// PIX & Meios de Pagamento
async function openPixModal(id, nome) {
  document.getElementById('pix-estab-id').value = id;
  document.getElementById('pix-estab-nome').textContent = nome;
  document.getElementById('pix-new-chave').value = '';
  document.getElementById('pix-new-obs').value = '';

  const list = document.getElementById('pix-keys-list');
  list.innerHTML = '<div class="text-slate-500 py-3 text-center">Carregando meios de pagamento...</div>';
  openModal('modal-pix');

  try {
    const keys = await apiRequest(`/api/estabelecimentos/${id}/meios-pagamento`);
    if (keys.length === 0) {
      list.innerHTML = '<div class="text-slate-500 py-3 text-center">Nenhum meio de pagamento ou chave PIX cadastrada.</div>';
      return;
    }

    list.innerHTML = keys.map(k => `
      <div class="p-3 rounded-xl bg-slate-50 border border-slate-200 flex items-center justify-between">
        <div>
          <div class="font-bold text-slate-900 flex items-center gap-2">
            <span class="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">${k.tipo}</span>
            <span class="font-mono text-emerald-700 font-bold select-all">${k.chaveOuDetalhe}</span>
          </div>
          <div class="text-[11px] text-slate-500 font-medium mt-1">${k.descricao || ''}</div>
        </div>
        <button onclick="removerMeioPagamento(${k.id})" class="p-1.5 text-slate-400 hover:text-rose-600 transition rounded-lg hover:bg-rose-50" title="Remover">
          <i data-lucide="trash-2" class="w-4 h-4"></i>
        </button>
      </div>
    `).join('');
    if (window.lucide) lucide.createIcons();
  } catch (err) {
    list.innerHTML = `<div class="text-rose-400 py-2">Erro ao carregar chaves: ${err.message}</div>`;
  }
}

async function adicionarMeioPagamento() {
  const estabId = document.getElementById('pix-estab-id').value;
  const tipo = document.getElementById('pix-new-tipo').value;
  const chave = document.getElementById('pix-new-chave').value.trim();
  const obs = document.getElementById('pix-new-obs').value.trim();

  if (!chave) {
    showToast('Informe a chave PIX ou detalhe da conta.', 'warning');
    return;
  }

  try {
    await apiRequest(`/api/estabelecimentos/${estabId}/meios-pagamento`, {
      method: 'POST',
      body: {
        tipo: tipo,
        chaveOuDetalhe: chave,
        descricao: obs,
        principal: true
      }
    });
    showToast('Meio de pagamento adicionado!', 'success');
    openPixModal(estabId, document.getElementById('pix-estab-nome').textContent);
  } catch (err) {
    showToast('Erro ao adicionar: ' + err.message, 'error');
  }
}

async function removerMeioPagamento(meioId) {
  const estabId = document.getElementById('pix-estab-id').value;
  if (!confirm('Deseja excluir este meio de pagamento?')) return;
  try {
    await apiRequest(`/api/estabelecimentos/${estabId}/meios-pagamento/${meioId}`, { method: 'DELETE' });
    showToast('Meio de pagamento excluído.', 'info');
    openPixModal(estabId, document.getElementById('pix-estab-nome').textContent);
  } catch (err) {
    showToast('Erro ao excluir: ' + err.message, 'error');
  }
}

// ============================================================================
// TAB 7: RELATÓRIO OPERAÇÕES & PDF
// ============================================================================

async function previewRelatorioOperacoes() {
  const estabId = document.getElementById('pdf-param-estab').value;
  const periodo = document.getElementById('pdf-param-periodo').value;
  const pelucia = document.getElementById('pdf-param-pelucia').value;
  const taxa = document.getElementById('pdf-param-taxa').value;
  const telemetria = document.getElementById('pdf-param-telemetria').value;
  const visitas = document.getElementById('pdf-param-visitas').value;
  const repasse = document.getElementById('pdf-param-repasse').value;

  let url = `/api/relatorios/operacoes/json?periodo=${periodo}&valorUnitarioPelucia=${pelucia}&taxaMaquininhaPercentual=${taxa}&mensalidadeTelemetria=${telemetria}&visitasManutencao=${visitas}&percentualRepasse=${repasse}`;
  if (estabId) url += `&estabelecimentoId=${estabId}`;

  try {
    const data = await apiRequest(url);

    document.getElementById('pdf-preview-title').textContent = data.tituloUnidade || 'Consolidado Geral';
    
    // Entradas
    const ent = data.entradas || {};
    document.getElementById('pdf-prev-entradas-total').textContent = formatMoney(ent.totalEntradas || 0);
    document.getElementById('pdf-prev-maquininha').textContent = formatMoney(ent.maquininha || 0);
    document.getElementById('pdf-prev-especie').textContent = formatMoney(ent.especie || 0);

    // Saídas
    const sai = data.saidas || {};
    document.getElementById('pdf-prev-saidas-total').textContent = formatMoney(sai.totalSaidas || 0);
    document.getElementById('pdf-prev-taxa-valor').textContent = formatMoney(sai.taxaMaquininha || 0);
    document.getElementById('pdf-prev-telemetria-valor').textContent = formatMoney(sai.mensalidadeSistema || 0);
    document.getElementById('pdf-prev-visitas-valor').textContent = formatMoney(sai.visitasManutencao || 0);
    document.getElementById('pdf-prev-pelucias-custo').textContent = formatMoney(sai.custoPelucias || 0);

    // Lucro & Repasse
    document.getElementById('pdf-prev-lucro-total').textContent = formatMoney(data.lucroTotal || 0);
    document.getElementById('pdf-prev-repasse-badge').textContent = `Repasse Acordado (${data.percentualRepasse || 25}%)`;
    document.getElementById('pdf-prev-repasse-valor').textContent = formatMoney(data.valorRepasse || 0);

    // Pelúcias
    const met = data.metricasPelucia || {};
    document.getElementById('pdf-prev-pelucias-qtd').textContent = `${met.peluciasSaidas || 0} un`;
    document.getElementById('pdf-prev-pelucias-unit').textContent = formatMoney(met.valorUnitarioPelucia || 19.9);
    document.getElementById('pdf-prev-rec-premio').textContent = formatMoney(met.receitaPorPremio || 0);
  } catch (err) {
    console.error('Erro no preview do relatório:', err);
  }
}

async function downloadRelatorioPdf() {
  const estabId = document.getElementById('pdf-param-estab').value;
  const periodo = document.getElementById('pdf-param-periodo').value;
  const pelucia = document.getElementById('pdf-param-pelucia').value;
  const taxa = document.getElementById('pdf-param-taxa').value;
  const telemetria = document.getElementById('pdf-param-telemetria').value;
  const visitas = document.getElementById('pdf-param-visitas').value;
  const repasse = document.getElementById('pdf-param-repasse').value;

  let url = `/api/relatorios/pdf?periodo=${periodo}&valorUnitarioPelucia=${pelucia}&taxaMaquininhaPercentual=${taxa}&mensalidadeTelemetria=${telemetria}&visitasManutencao=${visitas}&percentualRepasse=${repasse}`;
  if (estabId) url += `&estabelecimentoId=${estabId}`;

  showToast('Gerando documento PDF oficial...', 'info');

  try {
    const blob = await apiRequest(url);
    const downloadUrl = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = `relatorio-operacoes-${periodo}.pdf`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(downloadUrl);
    showToast('Download do PDF iniciado!', 'success');
  } catch (err) {
    showToast('Erro ao baixar PDF: ' + err.message, 'error');
  }
}

function openPdfModal() {
  switchTab('relatorios');
}

// ============================================================================
// MODAL & DOM HELPERS
// ============================================================================

function openModal(modalId) {
  const modal = document.getElementById(modalId);
  if (modal) {
    modal.classList.remove('hidden');
    if (window.lucide) lucide.createIcons();
  }
}

function closeModal(modalId) {
  const modal = document.getElementById(modalId);
  if (modal) {
    modal.classList.add('hidden');
  }
}

function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  let bg = 'bg-slate-900 border-slate-800 text-white';
  let icon = 'info';

  if (type === 'success') {
    bg = 'bg-emerald-600 border-emerald-500 text-white shadow-lg shadow-emerald-600/20';
    icon = 'check-circle';
  } else if (type === 'error') {
    bg = 'bg-rose-600 border-rose-500 text-white shadow-lg shadow-rose-600/20';
    icon = 'alert-octagon';
  } else if (type === 'warning') {
    bg = 'bg-amber-600 border-amber-500 text-white shadow-lg shadow-amber-600/20';
    icon = 'alert-triangle';
  }

  toast.className = `p-3.5 rounded-2xl border shadow-2xl flex items-center gap-3 text-xs font-semibold max-w-sm pointer-events-auto transition-all duration-300 animate-fade-in ${bg}`;
  toast.innerHTML = `
    <i data-lucide="${icon}" class="w-4 h-4 shrink-0"></i>
    <span class="flex-1 font-medium">${message}</span>
  `;

  container.appendChild(toast);
  if (window.lucide) lucide.createIcons();

  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    setTimeout(() => toast.remove(), 300);
  }, 4000);
}

function formatMoney(amount) {
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(amount || 0);
}

function parseMoney(str) {
  if (!str) return 0;
  return parseFloat(str.replace(/[^0-9,-]+/g, '').replace(',', '.')) || 0;
}

function formatDate(dateStr) {
  if (!dateStr) return '--';
  try {
    const d = new Date(dateStr);
    if (isNaN(d.getTime())) return dateStr;
    return d.toLocaleString('pt-BR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  } catch (e) {
    return dateStr;
  }
}

function formatDateTime(dateStr) {
  return formatDate(dateStr);
}

async function loadInitialLookups() {
  try {
    const [stores, machines] = await Promise.all([
      apiRequest('/api/estabelecimentos'),
      apiRequest('/api/maquinas')
    ]);
    state.stores = stores;
    state.machines = machines;

    // Extract unique list of cities
    const citySet = new Set();
    stores.forEach(s => {
      if (s.cidade && s.cidade.trim()) citySet.add(s.cidade.trim());
    });
    machines.forEach(m => {
      if (m.cidade && m.cidade.trim()) citySet.add(m.cidade.trim());
      if (m.estabelecimento?.cidade && m.estabelecimento.cidade.trim()) citySet.add(m.estabelecimento.cidade.trim());
    });
    state.cities = Array.from(citySet).sort();

    // Populate City Buttons in Dashboard
    const cityGroup = document.getElementById('city-buttons-group');
    if (cityGroup) {
      let cityHtml = `<button type="button" class="filter-btn ${state.currentCity === '' ? 'active' : ''}" data-city="" onclick="setDashboardCity('')">Todas as Cidades</button>`;
      state.cities.forEach(c => {
        cityHtml += `<button type="button" class="filter-btn ${state.currentCity === c ? 'active' : ''}" data-city="${c}" onclick="setDashboardCity('${c}')">${c}</button>`;
      });
      cityGroup.innerHTML = cityHtml;
    }

    // Populate Machine Select in Dashboard
    filterMachineSelectByCity(state.currentCity);

    // Populate PDF params dropdown
    const pdfEstab = document.getElementById('pdf-param-estab');
    if (pdfEstab) {
      const cur = pdfEstab.value;
      pdfEstab.innerHTML = '<option value="">Consolidado de Todos</option>' +
        stores.map(s => `<option value="${s.id}">${s.nome}</option>`).join('');
      pdfEstab.value = cur;
    }
  } catch (e) {
    console.error('Lookups failed:', e);
  }
}

function populateEstabelecimentosSelect(selectId, selectedId = null) {
  const el = document.getElementById(selectId);
  if (!el) return;
  el.innerHTML = '<option value="">Sem Estabelecimento (Depósito/Sem Loja)</option>' +
    state.stores.map(s => `<option value="${s.id}" ${s.id === selectedId ? 'selected' : ''}>${s.nome} (${s.cidade || ''})</option>`).join('');
}

function populateMaquinasSelect(selectId, selectedId = null) {
  const el = document.getElementById(selectId);
  if (!el) return;
  el.innerHTML = '<option value="">Selecione a Máquina...</option>' +
    state.machines.map(m => {
      const name = m.nomeIntegracao || m.descricao || `Máquina #${m.id}`;
      const store = m.estabelecimento?.nome ? ` - ${m.estabelecimento.nome}` : '';
      return `<option value="${m.id}" ${m.id === selectedId ? 'selected' : ''}>${name}${store}</option>`;
    }).join('');
}

// ============================================================================
// AUTHENTICATION & LOGIN FLOW
// ============================================================================

function showAuthScreen() {
  document.getElementById('auth-screen')?.classList.remove('hidden');
  document.getElementById('app-container')?.classList.add('hidden');

  const savedUser = localStorage.getItem('playbox_saved_username') || localStorage.getItem('playbox_user') || 'admin';
  const userInput = document.getElementById('login-username');
  if (userInput) userInput.value = savedUser;

  const remChk = document.getElementById('login-remember');
  if (remChk) remChk.checked = localStorage.getItem('playbox_remember_login') !== 'false';

  if (window.lucide) lucide.createIcons();
}

function showAppScreen(username) {
  document.getElementById('auth-screen')?.classList.add('hidden');
  document.getElementById('app-container')?.classList.remove('hidden');
  const user = username || state.currentUser || 'admin';
  const userBadge = document.getElementById('header-user-badge');
  if (userBadge) userBadge.textContent = user;
  const sidebarUser = document.getElementById('sidebar-username');
  if (sidebarUser) sidebarUser.textContent = user;
  initSidebar();
  if (window.lucide) lucide.createIcons();
}

async function handleLogin(e) {
  if (e) e.preventDefault();
  const username = document.getElementById('login-username').value.trim();
  const password = document.getElementById('login-password').value;
  const remember = document.getElementById('login-remember')?.checked ?? true;
  const errorBox = document.getElementById('login-error-msg');
  const errorText = document.getElementById('login-error-text');
  const btn = document.getElementById('btn-login-submit');

  errorBox.classList.add('hidden');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner w-4 h-4 inline-block mr-2"></span> Autenticando...';

  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });

    const data = await res.json();

    if (!res.ok || data.status === 'ERROR') {
      throw new Error(data.message || 'Usuário ou senha incorretos.');
    }

    state.authToken = data.token;
    state.currentUser = data.username || username;
    localStorage.setItem('playbox_session_token', data.token);
    localStorage.setItem('playbox_user', state.currentUser);

    if (remember) {
      localStorage.setItem('playbox_remember_login', 'true');
      localStorage.setItem('playbox_saved_username', username);
    } else {
      localStorage.removeItem('playbox_remember_login');
      localStorage.removeItem('playbox_saved_username');
    }

    showAppScreen(state.currentUser);
    showToast(`Bem-vindo, ${state.currentUser}!`, 'success');

    await loadInitialLookups();
    await loadDashboardData();
  } catch (err) {
    errorText.textContent = err.message;
    errorBox.classList.remove('hidden');
    if (window.lucide) lucide.createIcons();
  } finally {
    btn.disabled = false;
    btn.innerHTML = '<i data-lucide="log-in" class="w-4 h-4"></i><span>Entrar no Sistema</span>';
    if (window.lucide) lucide.createIcons();
  }
}

async function handleLogout() {
  if (confirm('Deseja realmente sair do sistema?')) {
    try {
      if (state.authToken) {
        await fetch('/api/auth/logout', {
          method: 'POST',
          headers: { 'Authorization': `Bearer ${state.authToken}` }
        });
      }
    } catch (e) {}
    state.authToken = null;
    localStorage.removeItem('playbox_session_token');
    showAuthScreen();
    showToast('Sessão encerrada com sucesso.', 'info');
  }
}

// ============================================================================
// TRANSAÇÕES & TESTES
// ============================================================================

function setTransacoesPeriod(period) {
  state.transacoesPeriod = period;
  document.querySelectorAll('#transacoes-period-buttons-group .filter-btn').forEach(btn => {
    btn.classList.toggle('active', btn.getAttribute('data-tx-period') === period);
  });

  const customBar = document.getElementById('transacoes-custom-dates-bar');
  if (period === 'custom') {
    if (customBar) customBar.classList.remove('hidden');
  } else {
    if (customBar) customBar.classList.add('hidden');
    loadTransacoesData();
  }
}

function toggleTransacoesCustomDates() {
  setTransacoesPeriod('custom');
}

function applyTransacoesCustomDates() {
  const start = document.getElementById('transacoes-date-start').value;
  const end = document.getElementById('transacoes-date-end').value;
  if (!start || !end) {
    showToast('Informe as datas inicial e final para filtrar.', 'warning');
    return;
  }
  state.transacoesCustomStart = start;
  state.transacoesCustomEnd = end;
  loadTransacoesData();
}

async function loadTransacoesData(forceSync = false) {
  populateTransacoesMachineFilter();

  const machineId = document.getElementById('transacoes-filter-machine')?.value || '';
  let url = `/api/transacoes?`;

  if (state.transacoesPeriod === 'custom' && state.transacoesCustomStart && state.transacoesCustomEnd) {
    url += `dataInicio=${state.transacoesCustomStart}&dataFim=${state.transacoesCustomEnd}`;
  } else {
    url += `periodo=${encodeURIComponent(state.transacoesPeriod || 'mes')}`;
  }

  if (machineId) {
    url += `&maquinaId=${encodeURIComponent(machineId)}`;
  }

  if (forceSync) {
    url += `&sincronizar=true`;
    showToast('Sincronizando transações das integradoras...', 'info');
  }

  try {
    const list = await apiRequest(url);
    state.transacoes = Array.isArray(list) ? list : [];
    filterTransacoesTable();
  } catch (err) {
    console.error('Erro ao carregar transações:', err);
    showToast('Erro ao carregar transações: ' + err.message, 'error');
  }
}

function populateTransacoesMachineFilter() {
  const select = document.getElementById('transacoes-filter-machine');
  if (!select) return;
  const currentVal = select.value;

  const options = ['<option value="">Todas as Máquinas</option>'];
  (state.machines || []).forEach(m => {
    const nome = m.nomeIntegracao || m.descricao || `Máquina #${m.id}`;
    const est = m.estabelecimentoNome ? ` (${m.estabelecimentoNome})` : '';
    options.push(`<option value="${m.id}">${escapeHtml(nome + est)}</option>`);
  });

  select.innerHTML = options.join('');
  if (currentVal) select.value = currentVal;
}

function filterTransacoesTable() {
  const statusFilter = document.getElementById('transacoes-filter-status')?.value || '';
  const search = (document.getElementById('transacoes-search-input')?.value || '').toLowerCase().trim();

  let filtered = [...state.transacoes];

  if (statusFilter === 'VALIDAS') {
    filtered = filtered.filter(t => !t.teste);
  } else if (statusFilter === 'TESTES') {
    filtered = filtered.filter(t => t.teste);
  } else if (statusFilter === 'SEM_ESTABELECIMENTO') {
    filtered = filtered.filter(t => !t.estabelecimentoId || t.estabelecimentoNome === 'Sem Estabelecimento');
  }

  if (search) {
    filtered = filtered.filter(t => {
      const maq = (t.maquinaNome || '').toLowerCase();
      const est = (t.estabelecimentoNome || '').toLowerCase();
      const id = (t.externalTxId || '').toLowerCase();
      const int = (t.integracao || '').toLowerCase();
      return maq.includes(search) || est.includes(search) || id.includes(search) || int.includes(search);
    });
  }

  state.transacoesFilteredList = filtered;
  renderTransacoesKpis(filtered);
  renderTransacoesTable(filtered);
}

function renderTransacoesKpis(list) {
  let bruto = 0;
  let testes = 0;
  let efetivo = 0;
  let countTestes = 0;

  list.forEach(t => {
    const b = t.valorBruto || 0;
    const ef = t.valorEfetivo != null ? t.valorEfetivo : b;
    const est = t.valorEstorno || 0;
    const desc = Math.max(0, b - ef);

    bruto += b;
    testes += desc;
    efetivo += Math.max(0, ef - est);
    if (t.teste) countTestes++;
  });

  const elTotal = document.getElementById('tx-kpi-total');
  const elBruto = document.getElementById('tx-kpi-bruto');
  const elTestes = document.getElementById('tx-kpi-testes');
  const elTestesCount = document.getElementById('tx-kpi-testes-count');
  const elEfetivo = document.getElementById('tx-kpi-efetivo');
  const elBadge = document.getElementById('transacoes-count-badge');

  if (elTotal) elTotal.textContent = list.length;
  if (elBruto) elBruto.textContent = formatMoney(bruto);
  if (elTestes) elTestes.textContent = formatMoney(testes);
  if (elTestesCount) elTestesCount.textContent = `${countTestes} transação(ões) de teste`;
  if (elEfetivo) elEfetivo.textContent = formatMoney(efetivo);
  if (elBadge) elBadge.textContent = `${list.length} listadas`;
}

function renderTransacoesTable(list) {
  const tbody = document.getElementById('transacoes-table-body');
  const emptyState = document.getElementById('transacoes-empty-state');
  if (!tbody) return;

  if (!list || list.length === 0) {
    tbody.innerHTML = '';
    if (emptyState) emptyState.classList.remove('hidden');
    return;
  }

  if (emptyState) emptyState.classList.add('hidden');

  const rows = list.map(t => {
    const isTeste = Boolean(t.teste);
    const vt = t.valorTeste || 0;
    const motivo = t.motivoTeste || '';
    const bruto = t.valorBruto || 0;
    const cash = t.valorCash || 0;
    const pix = t.valorPix || 0;
    const cartao = (t.valorCredito || 0) + (t.valorDebito || 0);
    const efetivo = t.valorEfetivo != null ? t.valorEfetivo : bruto;

    let badgeStatus;
    if (isTeste) {
      if (vt > 0) {
        badgeStatus = `
          <div class="flex flex-col items-center">
            <span class="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[10px] font-extrabold bg-purple-100 text-purple-800 border border-purple-200">
              <i data-lucide="flask-conical" class="w-3 h-3"></i> TESTE PARCIAL (-${formatMoney(vt)})
            </span>
            ${motivo ? `<span class="text-[10px] text-slate-400 truncate max-w-[130px]" title="${escapeHtml(motivo)}">${escapeHtml(motivo)}</span>` : ''}
          </div>
        `;
      } else {
        badgeStatus = `
          <div class="flex flex-col items-center">
            <span class="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[10px] font-extrabold bg-rose-100 text-rose-800 border border-rose-200">
              <i data-lucide="flask-conical" class="w-3 h-3"></i> TESTE (100% DESC.)
            </span>
            ${motivo ? `<span class="text-[10px] text-slate-400 truncate max-w-[130px]" title="${escapeHtml(motivo)}">${escapeHtml(motivo)}</span>` : ''}
          </div>
        `;
      }
    } else {
      badgeStatus = `
        <span class="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[10px] font-bold bg-emerald-100 text-emerald-800 border border-emerald-200">
          <i data-lucide="check" class="w-3 h-3"></i> Válida
        </span>
      `;
    }

    const rowClass = isTeste ? 'bg-purple-50/30 hover:bg-purple-50/60 transition' : 'hover:bg-slate-50/80 transition';

    return `
      <tr class="${rowClass}">
        <td class="py-3 px-4 whitespace-nowrap">
          <div class="font-bold text-slate-900">${formatDateTime(t.dataHora)}</div>
          <div class="text-[10px] text-slate-400 font-mono">${escapeHtml(t.externalTxId || '')}</div>
        </td>
        <td class="py-3 px-4 font-bold text-slate-800">
          ${escapeHtml(t.maquinaNome || '--')}
        </td>
        <td class="py-3 px-4">
          ${(t.estabelecimentoId && t.estabelecimentoNome && t.estabelecimentoNome !== 'Sem Estabelecimento') ? `
            <div class="flex items-center gap-1.5">
              <span class="font-bold text-slate-800 text-xs">${escapeHtml(t.estabelecimentoNome)}</span>
              <button onclick="openModalAssociarEstabelecimentoIndividual(${t.id})" class="text-slate-400 hover:text-pink-600 transition" title="Alterar loja desta transação">
                <i data-lucide="edit-3" class="w-3 h-3"></i>
              </button>
            </div>
            ${t.cidade ? `<span class="text-[10px] text-slate-400 block">${escapeHtml(t.cidade)}</span>` : ''}
          ` : `
            <button onclick="openModalAssociarEstabelecimentoIndividual(${t.id})" class="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[10px] font-black bg-amber-100 text-amber-800 border border-amber-300 hover:bg-amber-200 shadow-sm transition" title="Clique para associar a um estabelecimento">
              <i data-lucide="store" class="w-3 h-3 text-amber-600"></i>
              <span>Sem Loja (+ Vincular)</span>
            </button>
          `}
        </td>
        <td class="py-3 px-4 whitespace-nowrap">
          <span class="text-[10px] font-bold px-2 py-0.5 rounded-md bg-slate-100 text-slate-700 border border-slate-200">
            ${escapeHtml(t.integracao || '')}
          </span>
        </td>
        <td class="py-3 px-4 text-right font-black ${isTeste ? 'text-slate-400 line-through' : 'text-pink-700'}">
          ${formatMoney(bruto)}
        </td>
        <td class="py-3 px-4 text-right font-semibold text-amber-700">
          ${formatMoney(cash)}
        </td>
        <td class="py-3 px-4 text-right font-medium text-slate-600 whitespace-nowrap">
          Pix: ${formatMoney(pix)}<br>
          <span class="text-[10px] text-slate-400">Card: ${formatMoney(cartao)}</span>
        </td>
        <td class="py-3 px-4 text-center">
          ${badgeStatus}
        </td>
        <td class="py-3 px-4 text-right font-black ${efetivo > 0 ? 'text-emerald-700' : 'text-slate-400'}">
          ${formatMoney(efetivo)}
        </td>
        <td class="py-3 px-4 text-center whitespace-nowrap">
          <button onclick="openModalTesteTransacao(${t.id})" class="px-2.5 py-1.5 rounded-lg ${isTeste ? 'bg-purple-100 text-purple-700 hover:bg-purple-200' : 'bg-slate-100 text-slate-700 hover:bg-purple-50 hover:text-purple-700'} text-[11px] font-bold transition flex items-center gap-1 mx-auto" title="Marcar ou editar status de teste desta transação">
            <i data-lucide="${isTeste ? 'edit' : 'flask-conical'}" class="w-3.5 h-3.5"></i>
            <span>${isTeste ? 'Editar Teste' : 'Marcar Teste'}</span>
          </button>
        </td>
      </tr>
    `;
  }).join('');

  tbody.innerHTML = rows;
  if (window.lucide) lucide.createIcons();
}

function openModalTesteTransacao(id) {
  const tx = state.transacoes.find(t => t.id === id);
  if (!tx) return;

  document.getElementById('teste-tx-id').value = tx.id;
  document.getElementById('teste-tx-maquina').textContent = tx.maquinaNome || '--';
  document.getElementById('teste-tx-estabelecimento').textContent = tx.estabelecimentoNome || 'Sem Estabelecimento';
  document.getElementById('teste-tx-data').textContent = formatDateTime(tx.dataHora);
  document.getElementById('teste-tx-bruto').textContent = formatMoney(tx.valorBruto || 0);
  document.getElementById('teste-tx-cash').textContent = formatMoney(tx.valorCash || 0);

  const isTeste = Boolean(tx.teste);
  const vt = tx.valorTeste || 0;
  const motivo = tx.motivoTeste || '';

  const chk = document.getElementById('teste-tx-is-teste');
  chk.checked = isTeste;

  const btnReverter = document.getElementById('btn-reverter-teste-tx');
  if (btnReverter) {
    if (isTeste) btnReverter.classList.remove('hidden');
    else btnReverter.classList.add('hidden');
  }

  if (isTeste && vt > 0) {
    document.getElementById('teste-tx-modo-parcial').checked = true;
    document.getElementById('teste-tx-valor').value = vt;
  } else {
    document.getElementById('teste-tx-modo-total').checked = true;
    document.getElementById('teste-tx-valor').value = '';
  }

  document.getElementById('teste-tx-motivo').value = motivo;

  toggleTesteTxInputs();
  openModal('modal-teste-transacao');
}

function toggleTesteTxInputs() {
  const isChecked = document.getElementById('teste-tx-is-teste').checked;
  const detailsWrap = document.getElementById('teste-tx-details-wrap');
  if (isChecked) {
    detailsWrap.classList.remove('hidden');
    toggleTesteTxModo();
  } else {
    detailsWrap.classList.add('hidden');
  }
}

function toggleTesteTxModo() {
  const modoParcial = document.getElementById('teste-tx-modo-parcial').checked;
  const parcialWrap = document.getElementById('teste-tx-valor-parcial-wrap');
  if (modoParcial) {
    parcialWrap.classList.remove('hidden');
  } else {
    parcialWrap.classList.add('hidden');
  }
}

async function salvarTesteTransacao() {
  const id = document.getElementById('teste-tx-id').value;
  if (!id) return;

  const isTeste = document.getElementById('teste-tx-is-teste').checked;
  let valorTeste = 0;
  let motivo = '';

  if (isTeste) {
    const modoParcial = document.getElementById('teste-tx-modo-parcial').checked;
    if (modoParcial) {
      const valStr = document.getElementById('teste-tx-valor').value;
      valorTeste = parseFloat(valStr) || 0;
      if (valorTeste <= 0) {
        showToast('Informe um valor de teste válido maior que zero.', 'warning');
        return;
      }
    }
    motivo = document.getElementById('teste-tx-motivo').value.trim();
  }

  try {
    const res = await apiRequest(`/api/transacoes/${id}/teste`, {
      method: 'PUT',
      body: {
        teste: isTeste,
        valorTeste: valorTeste,
        motivo: motivo
      }
    });

    closeModal('modal-teste-transacao');
    showToast(isTeste ? 'Transação desconsiderada como teste com sucesso!' : 'Transação revalidada como normal com sucesso!', 'success');
    
    // Atualiza a transação local e re-renderiza
    const idx = state.transacoes.findIndex(t => t.id === Number(id));
    if (idx !== -1 && res) {
      state.transacoes[idx] = res;
    }
    filterTransacoesTable();
  } catch (err) {
    console.error('Erro ao salvar ajuste de teste:', err);
    showToast('Erro ao salvar ajuste de teste: ' + err.message, 'error');
  }
}

async function reverterTesteTransacao() {
  const id = document.getElementById('teste-tx-id').value;
  if (!id) return;

  try {
    const res = await apiRequest(`/api/transacoes/${id}/teste`, {
      method: 'PUT',
      body: {
        teste: false,
        valorTeste: 0,
        motivo: null
      }
    });

    closeModal('modal-teste-transacao');
    showToast('Transação revalidada com sucesso! Não é mais considerada teste.', 'success');

    const idx = state.transacoes.findIndex(t => t.id === Number(id));
    if (idx !== -1 && res) {
      state.transacoes[idx] = res;
    }
    filterTransacoesTable();
  } catch (err) {
    console.error('Erro ao reverter teste da transação:', err);
    showToast('Erro ao reverter teste: ' + err.message, 'error');
  }
}

function openModalTestePeriodo() {
  const select = document.getElementById('teste-lote-maquina');
  if (select) {
    const options = ['<option value="">Todas as Máquinas</option>'];
    (state.machines || []).forEach(m => {
      const nome = m.nomeIntegracao || m.descricao || `Máquina #${m.id}`;
      const est = m.estabelecimentoNome ? ` (${m.estabelecimentoNome})` : '';
      options.push(`<option value="${m.id}">${escapeHtml(nome + est)}</option>`);
    });
    select.innerHTML = options.join('');

    // Pre-select current filter if any
    const currentFilter = document.getElementById('transacoes-filter-machine')?.value;
    if (currentFilter) select.value = currentFilter;
  }

  // Pre-fill dates
  const hoje = new Date();
  const inicioMes = new Date(hoje.getFullYear(), hoje.getMonth(), 1).toISOString().split('T')[0];
  const hojeStr = hoje.toISOString().split('T')[0];

  document.getElementById('teste-lote-inicio').value = state.transacoesCustomStart || inicioMes;
  document.getElementById('teste-lote-fim').value = state.transacoesCustomEnd || hojeStr;
  document.getElementById('teste-lote-valor').value = '';
  document.getElementById('teste-lote-motivo').value = '';
  document.getElementById('teste-lote-acao-marcar').checked = true;

  openModal('modal-teste-periodo');
}

async function aplicarTestePeriodoLote() {
  const maquinaId = document.getElementById('teste-lote-maquina').value;
  const dataInicio = document.getElementById('teste-lote-inicio').value;
  const dataFim = document.getElementById('teste-lote-fim').value;
  const isMarcar = document.getElementById('teste-lote-acao-marcar').checked;
  const valorStr = document.getElementById('teste-lote-valor').value;
  const valorTeste = parseFloat(valorStr) || 0;
  const motivo = document.getElementById('teste-lote-motivo').value.trim();

  if (!dataInicio || !dataFim) {
    showToast('Informe as datas de início e fim do período.', 'warning');
    return;
  }

  try {
    const res = await apiRequest('/api/transacoes/marcar-teste-periodo', {
      method: 'POST',
      body: {
        maquinaId: maquinaId ? Number(maquinaId) : null,
        dataInicio: dataInicio,
        dataFim: dataFim,
        teste: isMarcar,
        valorTeste: valorTeste,
        motivo: motivo
      }
    });

    closeModal('modal-teste-periodo');
    showToast(res.mensagem || 'Operação realizada com sucesso!', 'success');
    await loadTransacoesData();
  } catch (err) {
    console.error('Erro ao aplicar teste em lote:', err);
    showToast('Erro ao aplicar teste no período: ' + err.message, 'error');
  }
}

// ============================================================================
// ASSOCIAÇÃO MANUAL E HISTÓRICO DE MÁQUINAS E ESTABELECIMENTOS
// ============================================================================

function openModalAssociarEstabelecimentoLote() {
  const maqSelect = document.getElementById('associar-lote-maquina');
  const estabSelect = document.getElementById('associar-lote-estab');

  if (maqSelect) {
    maqSelect.innerHTML = '<option value="">Todas as Máquinas</option>' +
      (state.machines || []).map(m => {
        const nome = m.nomeIntegracao || m.descricao || `Máquina #${m.id}`;
        return `<option value="${m.id}">${escapeHtml(nome)}</option>`;
      }).join('');
  }

  if (estabSelect) {
    estabSelect.innerHTML = '<option value="">Selecione um estabelecimento...</option>' +
      (state.stores || []).map(s => {
        return `<option value="${s.id}">${escapeHtml(s.nome)} ${s.cidade ? `(${escapeHtml(s.cidade)})` : ''}</option>`;
      }).join('');
  }

  // Preencher datas padrão
  const inicioInput = document.getElementById('associar-lote-inicio');
  const fimInput = document.getElementById('associar-lote-fim');
  const hojeStr = new Date().toISOString().split('T')[0];
  const primeiroDiaMes = hojeStr.substring(0, 8) + '01';

  if (inicioInput && !inicioInput.value) inicioInput.value = primeiroDiaMes;
  if (fimInput && !fimInput.value) fimInput.value = hojeStr;

  openModal('modal-associar-estabelecimento-lote');
}

async function aplicarAssociacaoEstabelecimentoLote() {
  const maquinaId = document.getElementById('associar-lote-maquina')?.value;
  const estabId = document.getElementById('associar-lote-estab')?.value;
  const dataInicio = document.getElementById('associar-lote-inicio')?.value;
  const dataFim = document.getElementById('associar-lote-fim')?.value;
  const apenasOrfas = document.getElementById('associar-lote-apenas-orfas')?.checked ?? true;

  if (!estabId) {
    showToast('Selecione o estabelecimento de destino.', 'warning');
    return;
  }
  if (!dataInicio || !dataFim) {
    showToast('Informe as datas de início e fim do período.', 'warning');
    return;
  }

  try {
    const res = await apiRequest('/api/transacoes/associar-estabelecimento-lote', {
      method: 'POST',
      body: {
        maquinaId: maquinaId ? Number(maquinaId) : null,
        estabelecimentoId: Number(estabId),
        dataInicio: dataInicio,
        dataFim: dataFim,
        apenasSemEstabelecimento: apenasOrfas
      }
    });

    closeModal('modal-associar-estabelecimento-lote');
    showToast(res.mensagem || `${res.transacoesAfetadas} transações vinculadas com sucesso!`, 'success');
    await loadTransacoesData();
  } catch (err) {
    console.error('Erro ao associar em lote:', err);
    showToast('Falha ao associar transações: ' + err.message, 'error');
  }
}

function openModalAssociarEstabelecimentoIndividual(txId) {
  const tx = (state.transacoes || []).find(x => x.id === txId);
  if (!tx) {
    showToast('Transação não encontrada.', 'error');
    return;
  }

  document.getElementById('associar-indiv-tx-id').value = tx.id;
  document.getElementById('associar-indiv-external').textContent = tx.externalTxId || '--';
  document.getElementById('associar-indiv-maquina').textContent = tx.maquinaNome || '--';
  document.getElementById('associar-indiv-data').textContent = formatDateTime(tx.dataHora);
  document.getElementById('associar-indiv-valor').textContent = formatMoney(tx.valorBruto);

  const select = document.getElementById('associar-indiv-estab');
  if (select) {
    let opts = '<option value="">Sem Estabelecimento (Desvincular)</option>';
    (state.stores || []).forEach(s => {
      const isSel = (tx.estabelecimentoId && tx.estabelecimentoId === s.id);
      opts += `<option value="${s.id}" ${isSel ? 'selected' : ''}>${escapeHtml(s.nome)} ${s.cidade ? `(${escapeHtml(s.cidade)})` : ''}</option>`;
    });
    select.innerHTML = opts;
  }

  openModal('modal-associar-estabelecimento-individual');
}

async function salvarAssociacaoEstabelecimentoIndividual() {
  const txId = document.getElementById('associar-indiv-tx-id')?.value;
  const estabId = document.getElementById('associar-indiv-estab')?.value;

  if (!txId) return;

  try {
    const res = await apiRequest(`/api/transacoes/${txId}/estabelecimento?estabelecimentoId=${estabId || ''}`, {
      method: 'PUT'
    });

    closeModal('modal-associar-estabelecimento-individual');
    showToast('Estabelecimento da transação atualizado com sucesso!', 'success');
    await loadTransacoesData();
  } catch (err) {
    console.error('Erro ao vincular estabelecimento:', err);
    showToast('Falha ao salvar vínculo: ' + err.message, 'error');
  }
}

async function executarReassociacaoAutomatica() {
  if (!confirm('Deseja executar a associação automática de todas as transações sem loja com base no histórico temporal das máquinas?')) {
    return;
  }

  try {
    showToast('Processando reassociação automática...', 'info');
    const res = await apiRequest('/api/transacoes/reassociar-automatico', {
      method: 'POST'
    });

    showToast(res.mensagem || `${res.reassociadas} transações foram reassociadas!`, 'success');
    await loadTransacoesData();
  } catch (err) {
    console.error('Erro na reassociação automática:', err);
    showToast('Falha ao reassociar: ' + err.message, 'error');
  }
}

async function openHistoricoMaquinaModal(maquinaId) {
  if (!maquinaId) {
    showToast('ID da máquina não fornecido.', 'warning');
    return;
  }

  const maq = (state.machines || []).find(m => String(m.id) === String(maquinaId));
  const maqNome = maq ? (maq.nomeIntegracao || maq.descricao || `Máquina #${maquinaId}`) : `Máquina #${maquinaId}`;

  document.getElementById('hist-maq-subtitulo').textContent = `Linha do tempo de pontos comerciais: ${maqNome}`;

  const tbody = document.getElementById('hist-maq-tbody');
  tbody.innerHTML = '<tr><td colspan="5" class="py-8 text-center text-slate-400"><span class="spinner w-4 h-4 inline-block mr-2"></span> Carregando histórico...</td></tr>';

  openModal('modal-historico-vinculos-maquina');

  try {
    const list = await apiRequest(`/api/maquinas/${maquinaId}/historico-vinculos`);

    if (!list || list.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="5" class="py-8 text-center text-slate-400">
            Nenhum histórico de movimentação registrado para esta máquina.
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = list.map(h => {
      const isAtivo = h.ativo || !h.dataFim;
      return `
        <tr class="hover:bg-slate-50/80 transition">
          <td class="py-3 px-3">
            <div class="font-bold text-slate-900">${escapeHtml(h.estabelecimentoNome || 'Sem Loja')}</div>
            ${h.estabelecimentoCidade ? `<span class="text-[10px] text-slate-400">${escapeHtml(h.estabelecimentoCidade)}</span>` : ''}
          </td>
          <td class="py-3 px-3 font-semibold text-slate-700 whitespace-nowrap">
            ${formatDateTime(h.dataInicio)}
          </td>
          <td class="py-3 px-3 whitespace-nowrap">
            ${isAtivo ? `<span class="text-emerald-700 font-bold flex items-center gap-1"><span class="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span> Atualmente nesta loja</span>` : formatDateTime(h.dataFim)}
          </td>
          <td class="py-3 px-3">
            <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold ${isAtivo ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-600'}">
              ${isAtivo ? 'Ativa' : 'Encerrada'}
            </span>
          </td>
          <td class="py-3 px-3 text-slate-500 text-[11px]">
            ${escapeHtml(h.motivo || '--')}
          </td>
        </tr>
      `;
    }).join('');

    if (window.lucide) lucide.createIcons();
  } catch (err) {
    console.error('Erro ao carregar histórico da máquina:', err);
    tbody.innerHTML = `<tr><td colspan="5" class="py-8 text-center text-rose-500">Erro ao carregar histórico: ${err.message}</td></tr>`;
  }
}

// ============================================================================
// CONFIGURAÇÕES DE RELATÓRIO, AGENDAMENTOS PERSONALIZADOS & WHATSAPP
// ============================================================================

async function loadConfiguracoesRelatorio() {
  try {
    // 1. Carregar configurações gerais e grupos de WhatsApp
    const config = await apiRequest('/api/configuracoes/relatorio');
    if (config) {
      const reportChat = document.getElementById('config-wa-report-chat');
      if (reportChat) reportChat.value = config.whatsappReportChatId || '';

      const monitorChat = document.getElementById('config-wa-monitor-chat');
      if (monitorChat) monitorChat.value = config.whatsappMonitorChatId || '';

      const estoqueChat = document.getElementById('config-wa-estoque-chat');
      if (estoqueChat) estoqueChat.value = config.whatsappEstoqueChatId || '';

      const coletaChat = document.getElementById('config-wa-coleta-chat');
      if (coletaChat) coletaChat.value = config.whatsappColetaChatId || '';

      const schedMonitorEnabled = document.getElementById('config-sched-monitor-enabled');
      if (schedMonitorEnabled) schedMonitorEnabled.checked = config.scheduleMonitorEnabled ?? true;

      const schedMonitorInterval = document.getElementById('config-sched-monitor-interval');
      if (schedMonitorInterval) schedMonitorInterval.value = String(config.scheduleMonitorIntervalMinutes || 5);
    }

    // 2. Carregar relatórios agendados personalizados
    await loadRelatoriosAgendados();

    if (window.lucide) lucide.createIcons();
  } catch (err) {
    console.error('Erro ao carregar configurações de relatório:', err);
    showToast('Falha ao carregar configurações: ' + err.message, 'error');
  }
}

async function loadRelatoriosAgendados() {
  const tbody = document.getElementById('relatorios-agendados-tbody');
  const badgeTotal = document.getElementById('badge-total-agendamentos');
  if (!tbody) return;

  try {
    const lista = await apiRequest('/api/relatorios-agendados');
    state.relatoriosAgendados = Array.isArray(lista) ? lista : [];

    if (badgeTotal) {
      badgeTotal.textContent = `${state.relatoriosAgendados.length} agendamento${state.relatoriosAgendados.length === 1 ? '' : 's'}`;
    }

    if (state.relatoriosAgendados.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="8" class="py-12 text-center text-slate-400">
            <div class="flex flex-col items-center gap-2">
              <i data-lucide="calendar-x" class="w-8 h-8 text-slate-300"></i>
              <span class="font-semibold text-slate-500">Nenhum relatório agendado cadastrado</span>
              <p class="text-xs text-slate-400 max-w-sm">Clique no botão "+ Novo Relatório Agendado" acima para criar um envio automático personalizado.</p>
            </div>
          </td>
        </tr>
      `;
      if (window.lucide) lucide.createIcons();
      return;
    }

    tbody.innerHTML = state.relatoriosAgendados.map(item => {
      // Tipo Badge
      let tipoBadge = '';
      switch (item.tipo) {
        case 'FATURAMENTO_24H':
          tipoBadge = '<span class="px-2 py-0.5 rounded-full text-[10px] font-extrabold bg-pink-100 text-pink-700 border border-pink-200">24h Anterior</span>';
          break;
        case 'FATURAMENTO_12H':
          tipoBadge = '<span class="px-2 py-0.5 rounded-full text-[10px] font-extrabold bg-blue-100 text-blue-700 border border-blue-200">12h Parcial</span>';
          break;
        case 'FATURAMENTO_HOJE':
          tipoBadge = '<span class="px-2 py-0.5 rounded-full text-[10px] font-extrabold bg-indigo-100 text-indigo-700 border border-indigo-200">Hoje Parcial</span>';
          break;
        case 'FATURAMENTO_MES':
          tipoBadge = '<span class="px-2 py-0.5 rounded-full text-[10px] font-extrabold bg-purple-100 text-purple-700 border border-purple-200">Mês Acumulado</span>';
          break;
        case 'ALERTA_ESTOQUE':
          tipoBadge = '<span class="px-2 py-0.5 rounded-full text-[10px] font-extrabold bg-amber-100 text-amber-800 border border-amber-200">Alerta Estoque</span>';
          break;
        default:
          tipoBadge = `<span class="px-2 py-0.5 rounded-full text-[10px] font-bold bg-slate-100 text-slate-700">${escapeHtml(item.tipo || 'Relatório')}</span>`;
      }

      // Periodicidade
      let freqDesc = '';
      if (item.frequencia === 'DIARIO') {
        freqDesc = `<span class="font-semibold text-slate-800">Diário</span> às <span class="font-bold text-pink-700">${escapeHtml(item.hora || '00:00')}</span>`;
      } else if (item.frequencia === 'INTERVALO') {
        const mins = item.intervaloMinutos || 60;
        const texto = mins >= 60 ? `${Math.round(mins / 60)}h` : `${mins}min`;
        freqDesc = `A cada <span class="font-bold text-blue-700">${texto}</span>`;
      } else if (item.frequencia === 'MENSAL') {
        freqDesc = `Todo dia <span class="font-bold text-purple-700">${item.diaDoMes || 1}</span> às <span class="font-bold text-purple-700">${escapeHtml(item.hora || '08:00')}</span>`;
      } else {
        freqDesc = `<span class="font-mono text-[10px] text-slate-600">${escapeHtml(item.cronExpression || 'Cron')}</span>`;
      }

      // Detalhamento
      let detalheDesc = '';
      if (item.tipo === 'ALERTA_ESTOQUE') {
        detalheDesc = '<span class="text-slate-400 text-[11px]">—</span>';
      } else if (item.tipoExibicao === 'GERAL') {
        detalheDesc = '<span class="px-2 py-0.5 rounded-md text-[10px] font-bold bg-slate-100 text-slate-700">Resumo Geral</span>';
      } else {
        detalheDesc = '<span class="px-2 py-0.5 rounded-md text-[10px] font-bold bg-pink-50 text-pink-700 border border-pink-200" title="Oculta máquina se única, detalha se 2+">Por Ponto Comercial</span>';
      }

      // Destino WhatsApp
      let destinoWa = '';
      if (item.whatsappChatId && item.whatsappChatId.trim()) {
        const full = escapeHtml(item.whatsappChatId.trim());
        const short = full.length > 20 ? full.substring(0, 18) + '...' : full;
        destinoWa = `<span class="font-mono text-[11px] text-emerald-800 font-semibold" title="${full}">${short}</span>`;
      } else {
        destinoWa = '<span class="text-slate-400 text-[11px] italic">Grupo Padrão</span>';
      }

      // Último Disparo
      let disparoStatus = '';
      if (!item.ultimoDisparo) {
        disparoStatus = '<span class="text-slate-400 text-[11px]">Aguardando primeiro envio</span>';
      } else {
        const dt = formatDateTime(item.ultimoDisparo);
        const isSuccess = item.ultimoStatus === 'SUCCESS';
        disparoStatus = `
          <div class="space-y-0.5">
            <div class="text-[11px] font-medium text-slate-700">${dt}</div>
            <div class="flex items-center gap-1">
              <span class="inline-flex items-center gap-1 px-1.5 py-0.2 rounded-full text-[9px] font-bold ${isSuccess ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800'}">
                <span class="w-1.5 h-1.5 rounded-full ${isSuccess ? 'bg-emerald-500' : 'bg-rose-500'}"></span>
                ${isSuccess ? 'Sucesso' : 'Falha'}
              </span>
              ${item.ultimaMensagem ? `<button type="button" onclick="alert('${escapeHtml(item.ultimaMensagem)}')" class="text-slate-400 hover:text-slate-600 text-[10px]" title="Ver detalhes"><i data-lucide="info" class="w-3 h-3"></i></button>` : ''}
            </div>
          </div>
        `;
      }

      // Ativo Toggle
      const isAtivo = Boolean(item.ativo);
      const switchHtml = `
        <label class="relative inline-flex items-center cursor-pointer">
          <input type="checkbox" ${isAtivo ? 'checked' : ''} onchange="toggleAtivoRelatorioAgendado(${item.id})" class="sr-only peer">
          <div class="w-8 h-4 bg-slate-200 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[0px] after:left-[0px] after:bg-white after:border-slate-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-pink-600"></div>
        </label>
      `;

      return `
        <tr class="hover:bg-slate-50/80 transition ${isAtivo ? '' : 'opacity-60 bg-slate-50/30'}">
          <td class="py-3 px-3.5">
            <div class="font-bold text-slate-900 text-xs">${escapeHtml(item.nome || 'Sem Nome')}</div>
            <div class="text-[10px] text-slate-400 font-mono mt-0.5">ID #${item.id} • cron: ${escapeHtml(item.cronExpression || '')}</div>
          </td>
          <td class="py-3 px-3">${tipoBadge}</td>
          <td class="py-3 px-3">${freqDesc}</td>
          <td class="py-3 px-3">${detalheDesc}</td>
          <td class="py-3 px-3">${destinoWa}</td>
          <td class="py-3 px-3">${disparoStatus}</td>
          <td class="py-3 px-3 text-center">${switchHtml}</td>
          <td class="py-3 px-3.5 text-right whitespace-nowrap">
            <div class="flex items-center justify-end gap-1">
              <button onclick="dispararRelatorioAgendadoAgora(${item.id})" class="p-1.5 rounded-lg text-emerald-600 hover:text-emerald-700 hover:bg-emerald-50 transition" title="Disparar no WhatsApp Agora">
                <i data-lucide="send" class="w-4 h-4"></i>
              </button>
              <button onclick="abrirModalEditarRelatorioAgendado(${item.id})" class="p-1.5 rounded-lg text-slate-500 hover:text-slate-800 hover:bg-slate-100 transition" title="Editar Agendamento">
                <i data-lucide="pencil" class="w-4 h-4"></i>
              </button>
              <button onclick="excluirRelatorioAgendado(${item.id})" class="p-1.5 rounded-lg text-rose-500 hover:text-rose-700 hover:bg-rose-50 transition" title="Excluir Agendamento">
                <i data-lucide="trash-2" class="w-4 h-4"></i>
              </button>
            </div>
          </td>
        </tr>
      `;
    }).join('');

    if (window.lucide) lucide.createIcons();
  } catch (err) {
    console.error('Erro ao listar relatórios agendados:', err);
    tbody.innerHTML = `<tr><td colspan="8" class="py-8 text-center text-rose-500">Erro ao carregar lista de agendamentos: ${err.message}</td></tr>`;
  }
}

function onRelatorioTipoOrFreqChanged() {
  const tipo = document.getElementById('relatorio-tipo')?.value || 'FATURAMENTO_24H';
  const freq = document.getElementById('relatorio-frequencia')?.value || 'DIARIO';

  // Detalhamento só faz sentido para faturamentos
  const exibicaoContainer = document.getElementById('relatorio-tipo-exibicao-container');
  const dicaDetalhe = document.getElementById('relatorio-dica-detalhe');
  const isEstoque = (tipo === 'ALERTA_ESTOQUE');

  if (exibicaoContainer) exibicaoContainer.style.display = isEstoque ? 'none' : 'block';
  if (dicaDetalhe) dicaDetalhe.style.display = isEstoque ? 'none' : 'block';

  // Alterna contêineres de frequência
  const divDiario = document.getElementById('relatorio-freq-diario');
  const divIntervalo = document.getElementById('relatorio-freq-intervalo');
  const divMensal = document.getElementById('relatorio-freq-mensal');
  const divCron = document.getElementById('relatorio-freq-cron');

  if (divDiario) divDiario.classList.add('hidden');
  if (divIntervalo) divIntervalo.classList.add('hidden');
  if (divMensal) divMensal.classList.add('hidden');
  if (divCron) divCron.classList.add('hidden');

  switch (freq) {
    case 'INTERVALO':
      if (divIntervalo) divIntervalo.classList.remove('hidden');
      break;
    case 'MENSAL':
      if (divMensal) divMensal.classList.remove('hidden');
      break;
    case 'CRON':
      if (divCron) divCron.classList.remove('hidden');
      break;
    case 'DIARIO':
    default:
      if (divDiario) divDiario.classList.remove('hidden');
      break;
  }
}

function abrirModalNovoRelatorioAgendado() {
  const title = document.getElementById('modal-relatorio-agendado-title');
  if (title) title.innerHTML = '<i data-lucide="calendar-plus" class="w-4 h-4 text-pink-600"></i><span>Novo Relatório Agendado</span>';

  document.getElementById('relatorio-agendado-id').value = '';
  document.getElementById('relatorio-nome').value = '';
  document.getElementById('relatorio-tipo').value = 'FATURAMENTO_24H';
  document.getElementById('relatorio-tipo-exibicao').value = 'DETALHADO_ESTABELECIMENTO';
  document.getElementById('relatorio-frequencia').value = 'DIARIO';
  document.getElementById('relatorio-hora-diario').value = '08:00';
  document.getElementById('relatorio-intervalo-minutos').value = '60';
  document.getElementById('relatorio-dia-do-mes').value = '1';
  document.getElementById('relatorio-hora-mensal').value = '08:00';
  document.getElementById('relatorio-cron-expression').value = '';
  document.getElementById('relatorio-whatsapp-chat').value = '';
  document.getElementById('relatorio-ativo').checked = true;

  onRelatorioTipoOrFreqChanged();
  openModal('modal-relatorio-agendado');
  if (window.lucide) lucide.createIcons();
}

function abrirModalEditarRelatorioAgendado(id) {
  const item = (state.relatoriosAgendados || []).find(r => r.id === id);
  if (!item) {
    showToast('Agendamento não encontrado na lista local.', 'error');
    return;
  }

  const title = document.getElementById('modal-relatorio-agendado-title');
  if (title) title.innerHTML = '<i data-lucide="pencil" class="w-4 h-4 text-pink-600"></i><span>Editar Relatório Agendado</span>';

  document.getElementById('relatorio-agendado-id').value = item.id;
  document.getElementById('relatorio-nome').value = item.nome || '';
  document.getElementById('relatorio-tipo').value = item.tipo || 'FATURAMENTO_24H';
  document.getElementById('relatorio-tipo-exibicao').value = item.tipoExibicao || 'DETALHADO_ESTABELECIMENTO';
  document.getElementById('relatorio-frequencia').value = item.frequencia || 'DIARIO';
  document.getElementById('relatorio-hora-diario').value = item.hora || '00:00';
  document.getElementById('relatorio-intervalo-minutos').value = String(item.intervaloMinutos || 60);
  document.getElementById('relatorio-dia-do-mes').value = String(item.diaDoMes || 1);
  document.getElementById('relatorio-hora-mensal').value = item.hora || '08:00';
  document.getElementById('relatorio-cron-expression').value = item.cronExpression || '';
  document.getElementById('relatorio-whatsapp-chat').value = item.whatsappChatId || '';
  document.getElementById('relatorio-ativo').checked = Boolean(item.ativo);

  onRelatorioTipoOrFreqChanged();
  openModal('modal-relatorio-agendado');
  if (window.lucide) lucide.createIcons();
}

async function salvarRelatorioAgendado() {
  const id = document.getElementById('relatorio-agendado-id').value.trim();
  const nome = document.getElementById('relatorio-nome').value.trim();
  const tipo = document.getElementById('relatorio-tipo').value;
  const frequencia = document.getElementById('relatorio-frequencia').value;
  const tipoExibicao = document.getElementById('relatorio-tipo-exibicao').value;
  const whatsappChatId = document.getElementById('relatorio-whatsapp-chat').value.trim();
  const ativo = document.getElementById('relatorio-ativo').checked;

  if (!nome) {
    showToast('Por favor, informe um nome de identificação para o relatório.', 'warning');
    document.getElementById('relatorio-nome').focus();
    return;
  }

  let hora = null;
  let intervaloMinutos = null;
  let diaDoMes = null;
  let cronExpression = null;

  if (frequencia === 'DIARIO') {
    hora = document.getElementById('relatorio-hora-diario').value || '00:00';
  } else if (frequencia === 'INTERVALO') {
    intervaloMinutos = parseInt(document.getElementById('relatorio-intervalo-minutos').value || '60', 10);
  } else if (frequencia === 'MENSAL') {
    diaDoMes = parseInt(document.getElementById('relatorio-dia-do-mes').value || '1', 10);
    hora = document.getElementById('relatorio-hora-mensal').value || '08:00';
  } else if (frequencia === 'CRON') {
    cronExpression = document.getElementById('relatorio-cron-expression').value.trim();
    if (!cronExpression) {
      showToast('Informe a expressão cron desejada.', 'warning');
      return;
    }
  }

  const payload = {
    nome,
    tipo,
    frequencia,
    hora,
    intervaloMinutos,
    diaDoMes,
    cronExpression,
    tipoExibicao,
    whatsappChatId: whatsappChatId || null,
    ativo
  };

  try {
    if (id) {
      await apiRequest(`/api/relatorios-agendados/${id}`, {
        method: 'PUT',
        body: payload
      });
      showToast('Relatório agendado atualizado com sucesso!', 'success');
    } else {
      await apiRequest('/api/relatorios-agendados', {
        method: 'POST',
        body: payload
      });
      showToast('Novo relatório agendado criado com sucesso!', 'success');
    }

    closeModal('modal-relatorio-agendado');
    await loadRelatoriosAgendados();
  } catch (err) {
    console.error('Erro ao salvar relatório agendado:', err);
    showToast('Erro ao salvar agendamento: ' + err.message, 'error');
  }
}

async function toggleAtivoRelatorioAgendado(id) {
  try {
    const atualizado = await apiRequest(`/api/relatorios-agendados/${id}/toggle-ativo`, {
      method: 'PUT'
    });
    const statusMsg = atualizado.ativo ? 'Agendamento ativado!' : 'Agendamento pausado/desativado!';
    showToast(statusMsg, 'info');
    await loadRelatoriosAgendados();
  } catch (err) {
    console.error('Erro ao alterar status do agendamento:', err);
    showToast('Falha ao alterar status: ' + err.message, 'error');
    await loadRelatoriosAgendados();
  }
}

async function dispararRelatorioAgendadoAgora(id) {
  try {
    showToast('Disparando relatório no WhatsApp em tempo real...', 'info');
    const res = await apiRequest(`/api/relatorios-agendados/${id}/disparar-agora`, {
      method: 'POST'
    });
    if (res && res.success) {
      showToast(res.message || 'Relatório enviado com sucesso no WhatsApp!', 'success');
    } else {
      showToast(res.message || 'Falha ao disparar relatório.', 'warning');
    }
    await loadRelatoriosAgendados();
  } catch (err) {
    console.error('Erro ao disparar relatório agendado:', err);
    showToast('Erro ao disparar: ' + err.message, 'error');
    await loadRelatoriosAgendados();
  }
}

async function excluirRelatorioAgendado(id) {
  const item = (state.relatoriosAgendados || []).find(r => r.id === id);
  const nome = item ? `"${item.nome}"` : `ID #${id}`;

  if (!confirm(`Deseja realmente excluir o agendamento ${nome}?\nEssa ação cancelará seus disparos automáticos no servidor.`)) {
    return;
  }

  try {
    await apiRequest(`/api/relatorios-agendados/${id}`, {
      method: 'DELETE'
    });
    showToast('Agendamento excluído com sucesso!', 'success');
    await loadRelatoriosAgendados();
  } catch (err) {
    console.error('Erro ao excluir agendamento:', err);
    showToast('Erro ao excluir agendamento: ' + err.message, 'error');
  }
}

async function salvarConfiguracoesRelatorio() {
  try {
    const payload = {
      whatsappReportChatId: document.getElementById('config-wa-report-chat')?.value.trim() || '',
      whatsappMonitorChatId: document.getElementById('config-wa-monitor-chat')?.value.trim() || '',
      whatsappEstoqueChatId: document.getElementById('config-wa-estoque-chat')?.value.trim() || '',
      whatsappColetaChatId: document.getElementById('config-wa-coleta-chat')?.value.trim() || '',
      scheduleMonitorEnabled: document.getElementById('config-sched-monitor-enabled')?.checked ?? true,
      scheduleMonitorIntervalMinutes: parseInt(document.getElementById('config-sched-monitor-interval')?.value || '5', 10)
    };

    await apiRequest('/api/configuracoes/relatorio', {
      method: 'PUT',
      body: payload
    });

    showToast('Configurações de grupos WhatsApp salvas com sucesso!', 'success');
    await loadConfiguracoesRelatorio();
  } catch (err) {
    console.error('Erro ao salvar configurações:', err);
    showToast('Erro ao salvar configurações: ' + err.message, 'error');
  }
}

async function dispararRelatorioManual(periodo) {
  try {
    showToast(`Disparando relatório (${periodo})...`, 'info');
    const res = await apiRequest(`/api/trigger/report?periodo=${encodeURIComponent(periodo)}`, {
      method: 'POST'
    });
    showToast(res.message || 'Relatório disparado com sucesso no WhatsApp!', 'success');
  } catch (err) {
    console.error('Erro ao disparar relatório manual:', err);
    showToast('Falha ao disparar relatório: ' + err.message, 'error');
  }
}

async function testarEnvioWhatsApp(tipo = 'report') {
  try {
    showToast('Enviando mensagem de validação no WhatsApp...', 'info');
    const res = await apiRequest(`/api/configuracoes/relatorio/testar-envio?tipo=${encodeURIComponent(tipo)}`, {
      method: 'POST'
    });
    if (res && res.status === 'SUCCESS') {
      showToast(res.message || 'Teste enviado com sucesso no WhatsApp!', 'success');
    } else {
      showToast(res.message || 'Falha ao enviar mensagem de teste.', 'warning');
    }
  } catch (err) {
    console.error('Erro ao testar envio WhatsApp:', err);
    showToast('Erro no teste de WhatsApp: ' + err.message, 'error');
  }
}

// ============================================================================
// INITIALIZATION
// ============================================================================

document.addEventListener('DOMContentLoaded', async () => {
  if (window.lucide) lucide.createIcons();

  // Check if session token exists and is valid
  if (state.authToken) {
    try {
      const res = await fetch('/api/auth/me', {
        headers: { 'Authorization': `Bearer ${state.authToken}` }
      });
      if (res.ok) {
        const me = await res.json();
        showAppScreen(me.username);
        await loadInitialLookups();
        await loadDashboardData();
        return;
      }
    } catch (e) {
      console.warn('Erro ao validar sessão existente:', e);
    }
  }

  // Not logged in or session expired
  showAuthScreen();
});

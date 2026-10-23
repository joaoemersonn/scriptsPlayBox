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
// NAVIGATION & TABS
// ============================================================================

function switchTab(tabId) {
  state.activeTab = tabId;

  // Update tabs buttons styling
  document.querySelectorAll('.tab-btn').forEach(btn => {
    if (btn.getAttribute('data-tab') === tabId) {
      btn.className = 'tab-btn active-tab flex items-center gap-2 px-3.5 py-2 rounded-xl font-bold text-xs sm:text-sm transition whitespace-nowrap bg-pink-50 text-pink-700 border border-pink-200 shadow-sm';
    } else {
      btn.className = 'tab-btn flex items-center gap-2 px-3.5 py-2 rounded-xl font-semibold text-xs sm:text-sm transition whitespace-nowrap text-slate-600 hover:text-pink-600 hover:bg-pink-50/50';
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
    case 'estabelecimentos':
      loadEstabelecimentosData();
      break;
    case 'relatorios':
      previewRelatorioOperacoes();
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

    // Render KPIs
    renderDashboardKPIs(report, monitorStatus);

    // Render Stores Ranking List (DESTAQUE FATURAMENTO POR LOJA EM LISTA)
    renderStoresRankingList(report.porEstabelecimento || [], report.resumoGeral?.faturamentoBruto || 0);

    // Render Payment Methods Chart (FORMATO IMAGEM 1)
    renderPaymentMethodsChart(report.resumoGeral?.formasPagamento || {});

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

  populateEstabelecimentosSelect('modal-maquina-estab');
  openModal('modal-maquina');
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

  try {
    let saved;
    if (id) {
      saved = await apiRequest(`/api/maquinas/${id}`, { method: 'PUT', body: payload });
      // Update allocation if changed
      if (estabId) {
        await apiRequest(`/api/maquinas/${id}/alocar/${estabId}`, { method: 'PUT' });
      } else {
        await apiRequest(`/api/maquinas/${id}/desalocar`, { method: 'DELETE' });
      }
      showToast('Máquina atualizada com sucesso!', 'success');
    } else {
      saved = await apiRequest('/api/maquinas', { method: 'POST', body: payload });
      if (estabId && saved.id) {
        await apiRequest(`/api/maquinas/${saved.id}/alocar/${estabId}`, { method: 'PUT' });
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
    const conferidas = coletas.filter(c => c.status !== 'PENDENTE_CONFERENCIA').length;

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
    }

    return `
      <tr class="hover:bg-slate-50 transition border-b border-slate-100">
        <td class="p-3.5">
          <div class="font-extrabold text-slate-900">${c.maquinaNome}</div>
          <div class="text-[11px] text-slate-500">${c.estabelecimentoNome || 'Sem Loja'}</div>
        </td>
        <td class="p-3.5 text-slate-700 font-medium">${formatDate(c.dataHoraColeta)}</td>
        <td class="p-3.5 text-slate-500 text-[11px] font-medium">${c.dataHoraUltimaColeta ? formatDate(c.dataHoraUltimaColeta) : 'Primeira Coleta'}</td>
        <td class="p-3.5 font-bold text-amber-800 font-mono">${formatMoney(c.valorEsperado || 0)}</td>
        <td class="p-3.5 font-bold text-slate-900 font-mono">${c.valorReal != null ? formatMoney(c.valorReal) : '<em class="text-slate-400">Aguardando...</em>'}</td>
        <td class="p-3.5 font-mono">${difBadge}</td>
        <td class="p-3.5">${statusBadge}</td>
        <td class="p-3.5 text-right">
          ${c.status === 'PENDENTE_CONFERENCIA' ? `
            <button onclick="openConferirColetaModal(${c.id}, '${c.maquinaNome}', '${c.dataHoraColeta}', ${c.valorEsperado || 0})" class="px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-semibold text-xs transition">
              Conferir Malote
            </button>
          ` : `
            <button onclick="excluirColeta(${c.id})" class="p-1.5 rounded text-slate-500 hover:text-rose-400 transition" title="Excluir coleta">
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

// ============================================================================
// TAB 5: ALUGUÉIS & REPASSES
// ============================================================================

async function loadAlugueisData() {
  try {
    const monthPicker = document.getElementById('alugueis-month-picker');
    if (!monthPicker.value) {
      const today = new Date();
      monthPicker.value = today.toISOString().slice(0, 7);
    }
    const targetMonth = monthPicker.value;

    const [pendentes, pagos] = await Promise.all([
      apiRequest('/api/alugueis/pendentes'),
      apiRequest('/api/alugueis/pagos')
    ]);

    state.alugueis = { pendentes, pagos };

    // Filter by month
    const all = [...pendentes, ...pagos].filter(a => a.mesReferencia === targetMonth);

    const totalPagar = all.reduce((acc, a) => acc + (a.valorAluguel || 0), 0);
    const totalPago = all.filter(a => a.pago).reduce((acc, a) => acc + (a.valorAluguel || 0), 0);
    const totalPendente = all.filter(a => !a.pago).reduce((acc, a) => acc + (a.valorAluguel || 0), 0);
    const totalDinamicos = all.filter(a => a.estabelecimento?.tipoAluguel === 'DINAMICO').length;

    document.getElementById('alugueis-total-mes').textContent = formatMoney(totalPagar);
    document.getElementById('alugueis-total-pago').textContent = formatMoney(totalPago);
    document.getElementById('alugueis-total-pendente').textContent = formatMoney(totalPendente);
    document.getElementById('alugueis-total-dinamico').textContent = totalDinamicos;

    renderAlugueisTable(all);
    if (window.lucide) lucide.createIcons();
  } catch (err) {
    showToast('Erro ao carregar aluguéis: ' + err.message, 'error');
  }
}

function renderAlugueisTable(items) {
  const tbody = document.getElementById('alugueis-table-body');
  if (!tbody) return;

  if (items.length === 0) {
    tbody.innerHTML = `<tr><td colspan="8" class="p-8 text-center text-slate-500">Nenhum aluguel lançado para o mês selecionado. Clique em "Gerar Cobranças" para criar os lançamentos.</td></tr>`;
    return;
  }

  tbody.innerHTML = items.map(a => {
    const est = a.estabelecimento || {};
    const isDinamico = est.tipoAluguel === 'DINAMICO';
    const statusBadge = a.pago
      ? '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-50 text-emerald-700 border border-emerald-200">Pago</span>'
      : '<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-50 text-amber-700 border border-amber-200">Pendente</span>';

    return `
      <tr class="hover:bg-slate-50 transition border-b border-slate-100">
        <td class="p-3.5">
          <div class="font-extrabold text-slate-900">${est.nome || 'Loja Desconhecida'}</div>
          <div class="text-[11px] text-slate-500 font-medium">${est.responsavel ? 'Resp: ' + est.responsavel : ''}</div>
        </td>
        <td class="p-3.5 text-slate-700 text-xs font-medium">${est.cidade || '--'}</td>
        <td class="p-3.5 text-slate-700 text-xs font-mono font-bold">Dia ${est.diaPagamento || 10}</td>
        <td class="p-3.5">
          ${isDinamico ? `
            <span class="inline-flex items-center gap-1 text-[11px] font-bold text-pink-700">
              <i data-lucide="calculator" class="w-3.5 h-3.5 text-pink-600"></i> Dinâmico (${est.percentualRepasse || 25}%)
            </span>
          ` : `
            <span class="text-xs text-slate-600 font-medium">Fixo</span>
          `}
        </td>
        <td class="p-3.5 font-extrabold text-slate-900 font-mono text-sm">${formatMoney(a.valorAluguel || 0)}</td>
        <td class="p-3.5">${statusBadge}</td>
        <td class="p-3.5 text-xs text-slate-600 font-medium">
          ${a.pago ? `${formatDate(a.dataPagamento)} (${a.meioPagamento || 'PIX'})` : '--'}
        </td>
        <td class="p-3.5 text-right space-x-1 whitespace-nowrap">
          ${isDinamico ? `
            <button onclick="calcularDinamicoIndividual(${a.id})" class="px-2.5 py-1 rounded-lg bg-pink-50 hover:bg-pink-100 text-pink-700 border border-pink-200 text-xs font-bold transition" title="Recalcular 25% do lucro">
              Calcular 25%
            </button>
          ` : ''}

          <button onclick="openDefinirValorAluguelModal(${a.id}, '${est.nome}', ${a.valorAluguel || 0})" class="px-2.5 py-1 rounded-lg bg-white hover:bg-slate-100 border border-slate-300 text-slate-800 text-xs font-bold shadow-sm transition" title="Ajustar valor manual desse mês">
            Ajustar Valor
          </button>

          ${!a.pago ? `
            <button onclick="openPagarAluguelModal(${a.id}, '${est.nome}', '${a.mesReferencia}', ${a.valorAluguel || 0})" class="px-3 py-1 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs shadow-sm transition">
              Pagar
            </button>
          ` : `
            <button onclick="estornarAluguel(${a.id})" class="px-2.5 py-1 rounded-lg bg-rose-50 hover:bg-rose-100 border border-rose-200 text-rose-700 text-xs font-bold transition" title="Voltar para pendente">
              Estornar
            </button>
          `}

          <button onclick="openPixModal(${est.id}, '${est.nome}')" class="p-1.5 rounded-lg bg-white hover:bg-slate-100 border border-slate-300 text-slate-600 hover:text-emerald-600 shadow-sm transition" title="Ver PIX da Loja">
            <i data-lucide="qr-code" class="w-3.5 h-3.5"></i>
          </button>
        </td>
      </tr>
    `;
  }).join('');
}

async function gerarCobrancasMes() {
  const month = document.getElementById('alugueis-month-picker').value;
  showToast(`Gerando cobranças para o mês ${month}...`, 'info');
  try {
    const gerados = await apiRequest(`/api/alugueis/gerar-mes?mesAno=${month}`, { method: 'POST' });
    showToast(`Cobranças geradas com sucesso! Total: ${gerados.length || 0}`, 'success');
    loadAlugueisData();
  } catch (err) {
    showToast('Erro ao gerar cobranças: ' + err.message, 'error');
  }
}

async function recalcularDinamicosMes() {
  const month = document.getElementById('alugueis-month-picker').value;
  showToast(`Recalculando aluguéis dinâmicos (25%) para ${month}...`, 'info');
  try {
    const atualizados = await apiRequest(`/api/alugueis/calcular-dinamicos?mesAno=${month}`, { method: 'POST' });
    showToast(`Recalculados com sucesso! ${atualizados.length || 0} lojas dinâmicas.`, 'success');
    loadAlugueisData();
  } catch (err) {
    showToast('Erro ao recalcular: ' + err.message, 'error');
  }
}

async function calcularDinamicoIndividual(id) {
  showToast('Calculando aluguel dinâmico (25% do lucro)...', 'info');
  try {
    await apiRequest(`/api/alugueis/${id}/calcular-dinamico`, { method: 'POST' });
    showToast('Aluguel dinâmico recalculado!', 'success');
    loadAlugueisData();
  } catch (err) {
    showToast('Erro ao calcular: ' + err.message, 'error');
  }
}

function openDefinirValorAluguelModal(id, loja, valorAtual) {
  document.getElementById('valor-aluguel-id').value = id;
  document.getElementById('valor-aluguel-loja').textContent = loja;
  document.getElementById('valor-aluguel-novo').value = valorAtual;
  document.getElementById('valor-aluguel-obs').value = '';
  openModal('modal-valor-aluguel');
}

async function confirmarDefinirValorAluguel() {
  const id = document.getElementById('valor-aluguel-id').value;
  const valor = parseFloat(document.getElementById('valor-aluguel-novo').value);
  const obs = document.getElementById('valor-aluguel-obs').value.trim();

  if (isNaN(valor) || valor < 0) {
    showToast('Informe um valor válido.', 'warning');
    return;
  }

  try {
    let url = `/api/alugueis/${id}/valor?valor=${valor}`;
    if (obs) url += `&obs=${encodeURIComponent(obs)}`;
    await apiRequest(url, { method: 'PUT' });
    showToast('Valor do aluguel atualizado com sucesso!', 'success');
    closeModal('modal-valor-aluguel');
    loadAlugueisData();
  } catch (err) {
    showToast('Erro ao definir valor: ' + err.message, 'error');
  }
}

function openPagarAluguelModal(id, loja, mes, valor) {
  document.getElementById('pagar-aluguel-id').value = id;
  document.getElementById('pagar-aluguel-loja').textContent = loja;
  document.getElementById('pagar-aluguel-mes').textContent = mes;
  document.getElementById('pagar-aluguel-valor').textContent = formatMoney(valor);

  const today = new Date().toISOString().slice(0, 10);
  document.getElementById('pagar-aluguel-data').value = today;
  document.getElementById('pagar-aluguel-meio').value = 'PIX';
  document.getElementById('pagar-aluguel-obs').value = '';

  openModal('modal-pagar-aluguel');
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
    loadAlugueisData();
  } catch (err) {
    showToast('Erro ao registrar pagamento: ' + err.message, 'error');
  }
}

async function estornarAluguel(id) {
  if (!confirm('Deseja estornar o pagamento deste aluguel e colocá-lo como pendente?')) return;
  try {
    await apiRequest(`/api/alugueis/${id}/estornar`, { method: 'PUT' });
    showToast('Pagamento estornado com sucesso!', 'info');
    loadAlugueisData();
  } catch (err) {
    showToast('Erro ao estornar: ' + err.message, 'error');
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
  if (window.lucide) lucide.createIcons();
}

function showAppScreen(username) {
  document.getElementById('auth-screen')?.classList.add('hidden');
  document.getElementById('app-container')?.classList.remove('hidden');
  const userBadge = document.getElementById('header-user-badge');
  if (userBadge) userBadge.textContent = username || state.currentUser || 'admin';
  if (window.lucide) lucide.createIcons();
}

async function handleLogin(e) {
  if (e) e.preventDefault();
  const username = document.getElementById('login-username').value.trim();
  const password = document.getElementById('login-password').value;
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

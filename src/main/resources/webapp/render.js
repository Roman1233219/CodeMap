// render.js - CodeMap Renderer v31 FULL (Recursive Branches & Root Context Fix)

let allFiles = [];
let globalPsiData = null;
let allFunctionsMap = new Map();
let systemDescriptions = {};
let selectedBranches = {}; // Хранит выбор: { "funcId.branchIndex": variantIndex }
let activeFilters = {
    "LOG": true, "UI": true, "ASYNC": true, "STORAGE": true, "NETWORK": true,
    "ANDROID": true, "STATE": true, "ERROR": true, "INTERNAL": true, "SYSTEM": true,
    "ACTION": true, "REQUEST": true, "DATA_FLOW": true
};
window.isGlobalCollapsed = true;
window.currentAnalysisRoot = null; // Хранит текущую анализируемую функцию (корень цепочки)

window.globalPsiData = null;
window.allFunctionsMap = allFunctionsMap;

// --- ЗАГРУЗКА ДАННЫХ ---
async function loadSystemDescriptions() {
    if (window.SYSTEM_FUNCTIONS && Object.keys(window.SYSTEM_FUNCTIONS).length > 0) {
        systemDescriptions = window.SYSTEM_FUNCTIONS;
        return;
    }
    try {
        const response = await fetch('system_functions.json');
        systemDescriptions = await response.json();
    } catch (e) {}
}

async function loadAndRender() {
    try {
        await loadSystemDescriptions();
        globalPsiData = (window.PSI_DATA && window.PSI_DATA.files) ? window.PSI_DATA : await (await fetch('PSI.json')).json();
        window.globalPsiData = globalPsiData;

        if (typeof AndroidComponentDetector !== 'undefined') {
            window.androidDetector = new AndroidComponentDetector(globalPsiData);
        }

        buildFunctionsMap(globalPsiData);
        allFiles = extractFiles(globalPsiData);
        renderFileTree(allFiles);
        initContextMenu();
    } catch (error) {
        console.error("Ошибка загрузки:", error);
    }
}

// --- КОНТЕКСТНОЕ МЕНЮ ---
function initContextMenu() {
    const menu = document.getElementById('context-menu') || document.createElement('div');
    menu.id = 'context-menu';
    menu.style.cssText = `
        display: none; position: fixed; z-index: 10000;
        background: #1e1e1e; border: 1px solid #3c3c3c; border-radius: 4px;
        box-shadow: 0 4px 12px rgba(0,0,0,0.5); padding: 4px 0; min-width: 160px;
    `;
    if (!menu.parentElement) document.body.appendChild(menu);

    document.addEventListener('click', () => menu.style.display = 'none');
    document.addEventListener('contextmenu', (e) => {
        const card = e.target.closest('.chain-card, .function-item');
        if (card && card.dataset.funcId) {
            e.preventDefault();
            const funcId = card.dataset.funcId;
            const func = allFunctionsMap.get(funcId);
            const fileData = globalPsiData.files.find(f => f.functions.some(fn => fn.id === funcId));

            if (func && fileData) {
                menu.innerHTML = `<div class="menu-item" style="padding: 8px 12px; cursor: pointer; color: #ccc; font-size: 12px;"
                    onclick="window.jumpToCode('${fileData.filePath.replace(/\\/g, '\\\\')}', ${func.lineStart})">
                    🔍 Перейти к коду
                </div>`;
                menu.style.display = 'block';
                menu.style.left = e.pageX + 'px';
                menu.style.top = e.pageY + 'px';
            }
        } else {
            menu.style.display = 'none';
        }
    });
}

function buildFunctionsMap(data) {
    allFunctionsMap.clear();
    data.files?.forEach(file => {
        file.functions?.forEach(func => allFunctionsMap.set(func.id, func));
    });
}

function extractFiles(data) {
    return data.files?.map(file => ({
        name: file.fileName,
        filePath: file.filePath,
        functions: file.functions || []
    })) || [];
}

function getFileNameFromId(id) { return id?.split('.')?.[0] || ''; }

function findFunctionById(id) {
    return allFunctionsMap.get(id);
}

function highlightFileByFuncId(id) {
    const fileName = getFileNameFromId(id);
    const headers = document.querySelectorAll('.file-header');
    headers.forEach(h => {
        if (h.querySelector('.file-name').textContent.includes(fileName)) {
            const exp = h.dataset.expanded === 'true';
            if (!exp) h.click();
            h.classList.add('highlighted');
            h.scrollIntoView({ behavior: 'smooth', block: 'center' });
            setTimeout(() => h.classList.remove('highlighted'), 2000);
        }
    });
}

// --- ПОИСК И ФИЛЬТРАЦИЯ ---

function applySearch(query) {
    const q = query.toLowerCase();
    const nodes = document.querySelectorAll('.file-tree-node');

    nodes.forEach(node => {
        const fileName = node.querySelector('.file-name').textContent.toLowerCase();
        const functions = node.querySelectorAll('.function-item');
        let hasVisibleFunc = false;

        functions.forEach(f => {
            const funcName = f.querySelector('.func-name').textContent.toLowerCase();
            const match = funcName.includes(q) || fileName.includes(q);
            f.style.display = match ? 'flex' : 'none';
            if (match) hasVisibleFunc = true;
        });

        node.style.display = (fileName.includes(q) || hasVisibleFunc) ? 'block' : 'none';
        if (q && (fileName.includes(q) || hasVisibleFunc)) {
            const header = node.querySelector('.file-header');
            if (header.dataset.expanded !== 'true') header.click();
        }
    });

    document.querySelectorAll('.chain-card').forEach(card => {
        const name = card.querySelector('.card-name')?.textContent.toLowerCase() || "";
        if (q && name.includes(q)) {
            card.style.border = "2px solid #00ff00";
            card.style.boxShadow = "0 0 15px #00ff00";
        } else {
            card.style.border = "";
            card.style.boxShadow = "";
        }
    });
}

function toggleFilter(type, isEnabled) {
    activeFilters[type] = isEnabled;
    if (type === "SYSTEM") {
        ["ACTION", "REQUEST", "DATA_FLOW", "INTERNAL"].forEach(t => activeFilters[t] = isEnabled);
    }
    if (window.currentAnalysisRoot) renderBidirectionalChain(window.currentAnalysisRoot);
}

function toggleGlobalCollapse() {
    window.isGlobalCollapsed = !window.isGlobalCollapsed;
    if (window.currentAnalysisRoot) renderBidirectionalChain(window.currentAnalysisRoot);
}

// --- ОТРИСОВКА ДЕРЕВА ---

function renderFileTree(files) {
    const container = document.getElementById('fileList');
    let html = '';
    files.forEach((file, i) => {
        const hasEntry = file.functions.some(f => f.isEntryPoint);
        html += `
            <div class="file-tree-node">
                <div class="file-header" data-file-id="${i}" data-expanded="false">
                    <span class="file-toggle">▶</span>
                    <span class="file-icon">${hasEntry ? '⭐' : '📄'}</span>
                    <span class="file-name">${escapeHtml(file.name)}</span>
                </div>
                <div class="file-functions" id="file-${i}" style="display: none;">
                    ${file.functions.map(f => `
                        <div class="function-item ${getTypeClass(f.type)} tooltip" data-func-id="${escapeHtml(f.id)}">
                            <span class="func-icon">${f.isEntryPoint ? '🚀' : '🔧'}</span>
                            <span class="func-name">${escapeHtml(f.name)}</span>
                            <div class="tooltip-text">${getFunctionTooltip(f)}</div>
                        </div>
                    `).join('')}
                </div>
            </div>`;
    });
    container.innerHTML = html;

    document.querySelectorAll('.file-header').forEach(h => {
        h.onclick = () => {
            const div = document.getElementById(`file-${h.dataset.fileId}`);
            const exp = h.dataset.expanded === 'true';
            div.style.display = exp ? 'none' : 'block';
            h.querySelector('.file-toggle').textContent = exp ? '▶' : '▼';
            h.dataset.expanded = !exp;
        };
    });

    document.querySelectorAll('.function-item').forEach(el => {
        el.onclick = () => {
            const func = allFunctionsMap.get(el.dataset.funcId);
            if (func) {
                selectedBranches = {};
                renderBidirectionalChain(func);
                highlightFileByFuncId(func.id);
            }
        };
    });
}

// --- ЛОГИКА ЦЕПОЧКИ (ФИКСИРОВАННАЯ) ---

function buildFullPath(func, visited = new Set()) {
    const path = { upstream: [], current: func, downstream: [] };
    if (!func || visited.has(func.id)) return path;
    visited.add(func.id);

    // Входящие
    func.inboundCalls?.forEach(inbound => {
        const parent = allFunctionsMap.get(inbound.functionId);
        if (parent && !visited.has(parent.id)) {
            path.upstream.push({ caller: parent });
        }
    });

    // Рекурсивный обработчик элементов
    function processContainer(calls, branches, depth) {
        // Обычные вызовы
        calls?.forEach(c => {
            const type = c.targetType || "INTERNAL";
            if (activeFilters[type] !== false) {
                const callee = allFunctionsMap.get(c.targetId);
                path.downstream.push({ type: 'call', data: c, callee: callee, depth: depth });

                // Если есть вложенные ветки в самом вызове (например в лямбде)
                if (c.branches?.length > 0) {
                    processContainer([], c.branches, depth + 1);
                }

                if (!window.isGlobalCollapsed && callee && !visited.has(callee.id)) {
                    processContainer(callee.calls, callee.branches, depth + 1);
                }
            }
        });

        // Ветвления
        branches?.forEach((branch, bIndex) => {
            const selectionKey = `${func.id}.${branch.lineNumber}.${bIndex}`;
            const selectedIdx = selectedBranches[selectionKey];

            if (selectedIdx !== undefined) {
                const variant = branch.branches[selectedIdx];
                path.downstream.push({ type: 'branch-header', data: branch, selectedVariant: variant, depth: depth });

                // Рекурсивно обрабатываем содержимое выбранной ветки
                processContainer(variant.nestedCalls, variant.branches, depth + 1);
            } else {
                path.downstream.push({ type: 'selector', data: branch, index: bIndex, funcId: func.id, key: selectionKey, depth: depth });
            }
        });
    }

    processContainer(func.calls, func.branches, 0);
    return path;
}

function selectBranch(selectionKey, variantIndex) {
    selectedBranches[selectionKey] = variantIndex;
    // Перерисовываем от текущего корня анализа, чтобы не терять контекст
    if (window.currentAnalysisRoot) {
        renderBidirectionalChain(window.currentAnalysisRoot);
    }
}

function renderBidirectionalChain(func) {
    window.currentAnalysisRoot = func; // Запоминаем корень
    const container = document.getElementById('content');
    const path = buildFullPath(func);

    let html = `
        <div class="chain-panel">
            <div class="chain-header">
                <button class="back-button" onclick="window.location.reload()">← Назад</button>
                <h2>🔗 Анализ: ${escapeHtml(func.name)}</h2>
            </div>
            <div class="chain-container">
                ${path.upstream.reverse().map(v => renderCompactCard(v.caller) + arrow()).join('')}

                <div class="chain-card ${getTypeClass(func.type)} active" data-func-id="${escapeHtml(func.id)}">
                    <div class="card-filename">📁 ${getFileNameFromId(func.id)}</div>
                    <div class="card-name">${func.isEntryPoint ? '🚀' : '🔧'} ${escapeHtml(func.name)}</div>
                    <div class="card-type">${func.type} (ROOT)</div>
                </div>

                ${arrow()}

                ${path.downstream.map(item => {
                    const indent = item.depth ? `style="margin-left: ${item.depth * 20}px;"` : '';
                    if (item.type === 'call') {
                        return `<div ${indent}>${(item.callee ? renderCompactCard(item.callee, item.data) : renderSystemCallCard(item.data))}</div>` + arrow();
                    }
                    if (item.type === 'selector') {
                        return `<div ${indent}>${renderBranchSelector(item.key, item.data)}</div>` + arrow();
                    }
                    if (item.type === 'branch-header') {
                        return `<div ${indent} style="color:#4caf50; font-size:10px; margin-bottom:8px;">↳ Выбрано: ${escapeHtml(item.selectedVariant.case)}</div>`;
                    }
                    return '';
                }).join('')}
            </div>
        </div>`;

    container.innerHTML = html;

    container.querySelectorAll('.chain-card[data-func-id]').forEach(el => {
        el.onclick = () => {
            const f = allFunctionsMap.get(el.dataset.funcId);
            if (f) {
                selectedBranches = {};
                renderBidirectionalChain(f);
                highlightFileByFuncId(f.id);
            }
        };
    });
}

function renderBranchSelector(selectionKey, branch) {
    return `
        <div class="branch-selector">
            <div class="branch-title">❓ Условие: ${escapeHtml(branch.condition || branch.type)}</div>
            <div class="branch-options">
                ${branch.branches.map((v, vIdx) => `
                    <div class="branch-option" onclick="selectBranch('${selectionKey}', ${vIdx})">
                        <span class="branch-case">${escapeHtml(v.case || 'вариант')}</span>
                        <span style="font-size:9px; opacity:0.5;">(${v.nestedCalls?.length || 0} вызовов)</span>
                    </div>
                `).join('')}
            </div>
        </div>
    `;
}

function arrow() { return '<div style="margin:8px 0; color:#333;">↓</div>'; }

function renderCompactCard(func, callData) {
    const type = callData?.targetType || func.type || 'UNKNOWN';
    const icon = callData ? getCallTypeIcon(type) : (func.isEntryPoint ? '🚀' : '🔧');
    return `
        <div class="chain-card call-type-${type.toLowerCase()}" data-func-id="${escapeHtml(func.id)}">
            <div class="card-filename">📁 ${getFileNameFromId(func.id)}</div>
            <div class="card-divider"></div>
            <div class="card-name">${icon} ${escapeHtml(func.name)}</div>
            <div class="card-type">${type}</div>
        </div>`;
}

function renderSystemCallCard(callData) {
    const type = callData.targetType || 'ACTION';
    const desc = systemDescriptions[callData.targetName];
    return `
        <div class="chain-card call-type-${type.toLowerCase()}">
            <div class="card-name">${getCallTypeIcon(type)} ${escapeHtml(callData.targetName)}</div>
            <div class="card-type">SYSTEM</div>
            ${desc ? `<div class="card-description">${escapeHtml(desc)}</div>` : ''}
        </div>`;
}

function getCallTypeIcon(type) {
    const icons = { 'ASYNC': '⏱️', 'UI': '🎨', 'STORAGE': '💾', 'ANDROID': '📱', 'STATE': '🔄', 'ERROR': '⚠️', 'LOG': '📝', 'NETWORK': '🌐', 'ACTION': '⚡', 'REQUEST': '📡' };
    return icons[type] || '🔧';
}

function getTypeClass(t) { return `type-${(t || 'unknown').toLowerCase()}`; }
function escapeHtml(t) { if(!t) return ""; const d = document.createElement('div'); d.textContent = t; return d.innerHTML; }
function getFunctionTooltip(f) {
    return `<div style="font-weight:bold;">📁 ${getFileNameFromId(f.id)}</div><div>🔧 ${escapeHtml(f.name)}</div><div style="border-top:1px solid #3a6a3a;margin:4px 0;"></div><div>📥 Входящие: ${f.inboundCalls?.length || 0}</div><div>📤 Исходящие: ${f.calls?.length || 0}</div>`;
}

// --- ЭКСПОРТ ---
window.loadAndRender = loadAndRender;
window.renderBidirectionalChain = renderBidirectionalChain;
window.selectBranch = selectBranch;
window.findFunctionById = findFunctionById;
window.highlightFileByFuncId = highlightFileByFuncId;
window.applySearch = applySearch;
window.toggleFilter = toggleFilter;
window.toggleGlobalCollapse = toggleGlobalCollapse;
window.allFunctionsMap = allFunctionsMap;

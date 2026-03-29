// render.js - CodeMap Renderer v23
// Полноценная двунаправленная визуализация с inboundCalls
// Сквозной путь: точка входа → текущая функция → конец цепочки

// Глобальные переменные
let allFiles = [];
let globalPsiData = null;
let allFunctionsMap = new Map();
let systemDescriptions = {};

// Экспортируем для других скриптов
window.globalPsiData = null;
window.allFunctionsMap = allFunctionsMap;
window.findFunctionByIdGlobal = findFunctionById;
window.renderBidirectionalChain = renderBidirectionalChain;

async function loadAndRender() {
    try {
        // Данные теперь приходят из window, инъектированные плагином
        globalPsiData = window.PSI_DATA || {};
        systemDescriptions = window.SYSTEM_FUNCTIONS || {};

        window.globalPsiData = globalPsiData;

        console.log(`✅ Загружено ${Object.keys(systemDescriptions).length} описаний системных функций`);

        // ИНИЦИАЛИЗИРУЕМ ДЕТЕКТОР СРАЗУ ПОСЛЕ ЗАГРУЗКИ ДАННЫХ
        if (typeof AndroidComponentDetector !== 'undefined') {
            window.androidDetector = new AndroidComponentDetector(globalPsiData);
            console.log('✅ Детектор инициализирован при загрузке');
        } else {
            console.warn('AndroidComponentDetector не найден, ждем загрузки');
        }

        buildFunctionsMap(globalPsiData);
        allFiles = extractFiles(globalPsiData);

        allFiles.sort((a, b) => {
            const aHasEntry = a.functions.some(f => f.isEntryPoint === true);
            const bHasEntry = b.functions.some(f => f.isEntryPoint === true);

            if (aHasEntry && !bHasEntry) return -1;
            if (!aHasEntry && bHasEntry) return 1;
            return a.name.localeCompare(b.name);
        });

        document.getElementById('counter').innerHTML = `Файлов: ${allFiles.length}`;
        renderFileTree(allFiles);

    } catch (error) {
        console.error('Ошибка загрузки:', error);
        const fileList = document.getElementById('fileList');
        const content = document.getElementById('content');
        if (fileList) fileList.innerHTML = `<div class="empty-state">[ ошибка: ${error.message} ]</div>`;
        if (content) content.innerHTML = `<div class="empty-state">[ ошибка: ${error.message} ]</div>`;
    }
}

function buildFunctionsMap(data) {
    allFunctionsMap.clear();
    if (!data.files) return;

    for (const file of data.files) {
        if (!file.functions) continue;
        for (const func of file.functions) {
            allFunctionsMap.set(func.id, func);
        }
    }
    console.log(`Построена карта функций: ${allFunctionsMap.size} функций`);
}

function extractFiles(data) {
    const files = [];
    if (!data.files) return files;

    for (const file of data.files) {
        if (!file.functions || file.functions.length === 0) continue;

        files.push({
            id: files.length,
            name: file.fileName,
            filePath: file.filePath,
            functions: file.functions.map(func => ({
                id: func.id,
                name: func.name,
                type: func.type,
                signature: func.signature,
                parameters: func.parameters,
                hasReturn: func.hasReturn,
                isEntryPoint: func.isEntryPoint || false,
                calls: func.calls || [],
                branches: func.branches || [],
                inboundCalls: func.inboundCalls || [],
                listenerLinks: func.listenerLinks || []
            }))
        });
    }

    return files;
}

function renderFileTree(files) {
    const container = document.getElementById('fileList');

    if (files.length === 0) {
        container.innerHTML = '<div class="empty-state">[ нет файлов ]</div>';
        return;
    }

    let html = '';
    for (let i = 0; i < files.length; i++) {
        const file = files[i];
        const fileId = `file-${i}`;
        const hasEntryPoint = file.functions.some(f => f.isEntryPoint === true);

        let pathHint = '';
        if (file.filePath) {
            const parts = file.filePath.split(/[\\/]/);
            const parentFolder = parts[parts.length - 2];
            if (parentFolder && parentFolder !== 'java' && parentFolder !== 'kotlin') {
                pathHint = ` [${parentFolder}]`;
            }
        }

        html += `
            <div class="file-tree-node">
                <div class="file-header ${hasEntryPoint ? 'entry-point' : ''}" data-file-id="${i}" data-expanded="false">
                    <span class="file-toggle">▶</span>
                    <span class="file-icon">${hasEntryPoint ? '⭐' : '📄'}</span>
                    <span class="file-name" title="${escapeHtml(file.filePath || '')}">
                        ${escapeHtml(file.name)}${pathHint ? `<span class="file-path-hint">${escapeHtml(pathHint)}</span>` : ''}
                    </span>
                    <span class="file-count">(${file.functions.length})</span>
                </div>
                <div class="file-functions" id="${fileId}" style="display: none;">
        `;

        for (const func of file.functions) {
            const typeClass = getTypeClass(func.type);
            const isEmpty = isFunctionBodyEmpty(func);
            const isSystem = window.androidDetector?.isSystemCallback(func);
            const tooltipHtml = getFunctionTooltip(func);

            let icon = '🔧';
            if (func.isEntryPoint) {
                icon = '🚀';
            } else if (isEmpty) {
                icon = '⚰️';
            } else if (isSystem) {
                icon = '⚙️';
            }

            html += `
                <div class="function-item ${typeClass} tooltip" data-func-id="${escapeHtml(func.id)}">
                    <span class="func-icon">${icon}</span>
                    <span class="func-name">${escapeHtml(func.name)}</span>
                    <span class="func-type-badge ${typeClass}">${getTypeLabel(func.type)}</span>
                    <span class="func-badge">📥 ${func.inboundCalls?.length || 0}</span>
                    <span class="func-badge">📤 ${func.calls?.length || 0}</span>
                    <div class="tooltip-text">${tooltipHtml}</div>
                </div>
            `;
        }

        html += `</div></div>`;
    }

    container.innerHTML = html;

    document.querySelectorAll('.file-header').forEach(header => {
        header.addEventListener('click', (e) => {
            e.stopPropagation();
            const fileId = header.dataset.fileId;
            const isExpanded = header.dataset.expanded === 'true';
            const functionsDiv = document.getElementById(`file-${fileId}`);
            const toggleSpan = header.querySelector('.file-toggle');

            if (isExpanded) {
                functionsDiv.style.display = 'none';
                toggleSpan.textContent = '▶';
                header.dataset.expanded = 'false';
            } else {
                functionsDiv.style.display = 'block';
                toggleSpan.textContent = '▼';
                header.dataset.expanded = 'true';
            }
        });
    });

    document.querySelectorAll('.function-item').forEach(el => {
        el.addEventListener('click', (e) => {
            e.stopPropagation();
            const funcId = el.dataset.funcId;
            const func = findFunctionById(funcId);
            if (func) {
                window.renderBidirectionalChain(func);
                document.querySelectorAll('.function-item').forEach(item => item.classList.remove('active'));
                el.classList.add('active');
                highlightFileByFuncId(funcId);
            }
        });
    });
}

function isFunctionBodyEmpty(func) {
    if (func.calls && func.calls.length > 0) return false;
    if (func.branches && func.branches.length > 0) return false;
    if (func.signature) {
        const sig = func.signature;
        if (sig.includes('{}')) return true;
        if (sig.includes('= Unit')) return true;
        if (sig.includes('override') && sig.includes(') = ')) return true;
        if (func.type === 'DATA_FLOW' && !sig.includes('{') && !sig.includes('return')) return true;
    }
    return false;
}

function findFunctionById(id) {
    if (allFunctionsMap.has(id)) return allFunctionsMap.get(id);
    for (const file of allFiles) {
        for (const func of file.functions) {
            if (func.id === id) return func;
        }
    }
    return null;
}

function getFileNameFromFuncId(funcId) {
    if (!funcId) return '';
    const parts = funcId.split('.');
    if (parts.length >= 2) return parts[0];
    return '';
}

function highlightFileByFuncId(funcId) {
    if (!funcId) return;
    const fileName = getFileNameFromFuncId(funcId);
    if (!fileName) return;

    const fileHeaders = document.querySelectorAll('.file-header');
    let targetHeader = null;
    for (const header of fileHeaders) {
        const fileTitle = header.querySelector('.file-name')?.innerText || '';
        if (fileTitle.includes(fileName)) {
            targetHeader = header;
            break;
        }
    }

    if (targetHeader) {
        fileHeaders.forEach(h => h.classList.remove('highlighted'));
        targetHeader.classList.add('highlighted');
        targetHeader.scrollIntoView({ behavior: 'smooth', block: 'center' });
        setTimeout(() => { if (targetHeader) targetHeader.classList.remove('highlighted'); }, 2000);
    }
}

function buildFullPath(func, visited = new Set()) {
    const path = { upstream: [], current: func, downstream: [] };
    if (visited.has(func.id)) return path;
    visited.add(func.id);

    if (func.inboundCalls && func.inboundCalls.length > 0) {
        for (const inbound of func.inboundCalls) {
            const parent = findFunctionById(inbound.functionId);
            if (parent && !visited.has(parent.id)) {
                path.upstream.push({ caller: parent, lineNumber: inbound.lineNumber, fileName: inbound.fileName });
                const parentPath = buildFullPath(parent, visited);
                path.upstream.push(...parentPath.upstream);
            }
        }
    }

    function processCalls(calls, callerId) {
        if (!calls) return;
        for (const call of calls) {
            if (call.nestedCalls) processCalls(call.nestedCalls, callerId);
            const target = findFunctionById(call.targetId);
            path.downstream.push({ callee: target, callData: call, callerId: callerId });
        }
    }

    processCalls(func.calls, func.id);

    if (func.listenerLinks) {
        for (const listenerId of func.listenerLinks) {
            const targetFunc = findFunctionById(listenerId);
            if (targetFunc) {
                path.downstream.push({
                    callee: targetFunc,
                    callData: { targetName: targetFunc.name, targetId: targetFunc.id, isListenerCall: true },
                    callerId: func.id
                });
            }
        }
    }

    return path;
}

function renderFullCard(func) {
    const typeClass = getTypeClass(func.type);
    const fileName = getFileNameFromFuncId(func.id);
    return `
        <div class="chain-card ${typeClass}" style="margin: 0 auto;">
            ${fileName ? `<div class="card-filename">📁 ${escapeHtml(fileName)}</div>` : ''}
            <div class="card-divider"></div>
            <div class="card-name">${escapeHtml(func.name)}</div>
            <div class="card-type">${getTypeLabel(func.type)}</div>
            <div class="card-stats">
                <span class="stat">📥 ${func.inboundCalls?.length || 0}</span>
                <span class="stat">📤 ${func.calls?.length || 0}</span>
            </div>
            ${func.isEntryPoint ? '<div class="card-badge entry">Точка входа</div>' : ''}
        </div>`;
}

function renderCompactCard(func, direction, callData = null, callerId = null) {
    const typeClass = getTypeClass(func.type);
    const lineInfo = callData?.lineNumber ? ` (line ${callData.lineNumber})` : '';
    const fileName = getFileNameFromFuncId(func.id);
    const clickableId = (callData?.isSystemCall || callData?.isListenerCall || !func.id) ? callerId : func.id;

    return `
        <div class="chain-card ${typeClass}" style="margin: 0 auto; cursor: pointer;" data-func-id="${escapeHtml(clickableId || '')}">
            ${fileName ? `<div class="card-filename">📁 ${escapeHtml(fileName)}</div>` : ''}
            <div class="card-divider"></div>
            <div class="card-name">${escapeHtml(func.name)}${lineInfo}</div>
            <div class="card-type">${getTypeLabel(func.type)}</div>
            <div class="card-stats">
                <span class="stat">📥 ${func.inboundCalls?.length || 0}</span>
                <span class="stat">📤 ${func.calls?.length || 0}</span>
            </div>
            ${callData?.isConditional ? '<div class="card-badge conditional">условно</div>' : ''}
            ${callData?.isSystemCall ? '<div class="card-badge system">System</div>' : ''}
            ${callData?.isListenerCall ? '<div class="card-badge listener">🎧 Слушатель</div>' : ''}
        </div>`;
}

function renderSystemCallCard(callData, callerId = null) {
    const description = systemDescriptions[callData.targetName] || null;
    return `
        <div class="chain-card type-unknown" style="margin: 0 auto; cursor: pointer;" data-func-id="${escapeHtml(callerId || '')}">
            <div class="card-name">${escapeHtml(callData.targetName)}</div>
            <div class="card-type">System</div>
            ${description ? `<div class="card-description" style="font-size:10px; color:#aaa; margin-top:5px;">${escapeHtml(description)}</div>` : ''}
            ${callData?.isCallback ? '<div class="card-badge callback">Callback</div>' : ''}
        </div>`;
}

function renderBidirectionalChain(func) {
    const contentDiv = document.getElementById('content');
    const path = buildFullPath(func);
    let html = '<div class="chain-panel">';
    html += `<div class="chain-header"><button class="back-button" onclick="window.location.reload()">← Назад</button><h2>🔗 ${escapeHtml(func.name)}</h2></div><div class="chain-container">`;

    if (path.upstream.length > 0) {
        html += '<div style="margin-bottom: 20px;"><div style="color: #88ff88; margin-bottom: 8px;">▲ ВЫЗЫВАЕТСЯ ИЗ:</div>';
        path.upstream.forEach((item, i) => {
            html += renderCompactCard(item.caller, 'upstream', null, item.caller.id);
            html += '<div style="text-align: center; margin: 4px 0;">↓</div>';
        });
        html += '</div>';
    }

    html += `<div style="margin-bottom: 20px;"><div style="color: #ffaa44; margin-bottom: 8px;">📍 ТЕКУЩАЯ:</div>${renderFullCard(func)}</div>`;

    if (path.downstream.length > 0) {
        html += '<div><div style="color: #88ff88; margin-bottom: 8px;">▼ ВЫЗЫВАЕТ:</div>';
        path.downstream.forEach((item, i) => {
            if (i > 0) html += '<div style="text-align: center; margin: 4px 0;">↓</div>';
            if (item.callee) html += renderCompactCard(item.callee, 'downstream', item.callData, item.callerId);
            else html += renderSystemCallCard(item.callData, item.callerId);
        });
        html += '</div>';
    }

    html += '</div></div>';
    contentDiv.innerHTML = html;

    document.querySelectorAll('.chain-card[data-func-id]').forEach(card => {
        card.addEventListener('click', () => {
            const f = findFunctionById(card.dataset.funcId);
            if (f) window.renderBidirectionalChain(f);
        });
    });
}

function getTypeClass(type) {
    switch (type) {
        case 'ACTION': return 'type-action';
        case 'REQUEST': return 'type-request';
        case 'DATA_FLOW': return 'type-dataflow';
        default: return 'type-unknown';
    }
}

function getTypeLabel(type) { return type || 'UNKNOWN'; }

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function getFunctionTooltip(func) {
    const fileName = getFileNameFromFuncId(func.id);
    let html = `<div style="font-weight: bold;">📁 ${escapeHtml(fileName)}</div><div>🔧 ${escapeHtml(func.name)}</div><div style="border-top: 1px solid #3a6a3a; margin: 4px 0;"></div>`;
    html += `<div>📥 Входящие: ${func.inboundCalls?.length || 0} | 📤 Исходящие: ${func.calls?.length || 0}</div>`;
    return html;
}

window.loadAndRender = loadAndRender;
window.findFunctionById = findFunctionById;
window.renderBidirectionalChain = renderBidirectionalChain;

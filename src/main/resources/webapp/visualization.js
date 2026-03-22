// ==================== visualization.js ====================
// Main visualization script for architecture dependencies

// ==================== GLOBAL VARIABLES ====================

window.INITIAL_DATA = window.INITIAL_DATA || null;
const BASE_LINE_SPACING = 10;

const blockConfig = {
    "Presentation": { x: 20, y: 275, w: 180, h: 50 },
    "Domain": { x: 215, y: 275, w: 180, h: 50 },
    "Data": { x: 410, y: 275, w: 180, h: 50 },
    "Infrastructure": { x: 605, y: 275, w: 180, h: 50 },
    "DI": { x: 800, y: 275, w: 180, h: 50 },
    "Common / Utils": { x: 995, y: 275, w: 185, h: 50 }
};

let fileToBlockMap = new Map();
let viewport, connLayer, dotsLayer, tooltip, svg, container, statsPanel;

function initDomElements() {
    viewport = document.getElementById('viewport');
    connLayer = document.getElementById('connections-layer');
    dotsLayer = document.getElementById('dots-layer');
    tooltip = document.getElementById('tooltip');
    svg = document.getElementById('main-svg');
    container = document.getElementById('canvas-container');
    statsPanel = document.getElementById('stats-panel');

    if (container) {
        container.onwheel = handleWheel;
        container.onmousedown = handleMouseDown;
    }
}

// ==================== ZOOM & PAN ====================

let scale = 1;
let tx = 0;
let ty = 0;
let isDragging = false;
let lastX, lastY;

function handleWheel(e) {
    e.preventDefault();
    const delta = e.deltaY > 0 ? 0.9 : 1.1;
    const pt = svg.createSVGPoint();
    pt.x = e.clientX; pt.y = e.clientY;
    const svgP = pt.matrixTransform(svg.getScreenCTM().inverse());

    tx = svgP.x - (svgP.x - tx) * delta;
    ty = svgP.y - (svgP.y - ty) * delta;
    scale *= delta;
    scale = Math.max(0.05, Math.min(20, scale));

    updateTransform();
    updateArrowMarkers();
    redrawConnections();
}

function handleMouseDown(e) {
    if(e.button === 0) {
        isDragging = true;
        lastX = e.clientX;
        lastY = e.clientY;
    }
}

window.onmousemove = (e) => {
    if (!isDragging) return;
    tx += e.clientX - lastX;
    ty += e.clientY - lastY;
    lastX = e.clientX;
    lastY = e.clientY;
    updateTransform();
};

window.onmouseup = () => { isDragging = false; };

function updateTransform() {
    if (viewport) viewport.setAttribute('transform', `translate(${tx},${ty}) scale(${scale})`);
}

function updateArrowMarkers() {
    if (!svg) return;
    let defs = svg.querySelector('defs') || svg.appendChild(document.createElementNS("http://www.w3.org/2000/svg", "defs"));
    defs.innerHTML = '';
    const arrowLen = Math.min(40, Math.max(12, 20 * scale));
    const arrowW = arrowLen / 2.5;

    const createM = (id, color) => {
        const m = document.createElementNS("http://www.w3.org/2000/svg", "marker");
        m.setAttribute("id", id);
        m.setAttribute("markerWidth", arrowLen);
        m.setAttribute("markerHeight", arrowW);
        m.setAttribute("refX", arrowLen - 1);
        m.setAttribute("refY", arrowW / 2);
        m.setAttribute("orient", "auto");
        const poly = document.createElementNS("http://www.w3.org/2000/svg", "polygon");
        poly.setAttribute("points", `0 0, ${arrowLen} ${arrowW / 2}, 0 ${arrowW}`);
        poly.setAttribute("fill", color);
        m.appendChild(poly);
        return m;
    };

    defs.appendChild(createM("arrowInCall", "#ffd700"));
    defs.appendChild(createM("arrowInData", "#000080"));
    defs.appendChild(createM("arrowInDataLight", "#00bfff"));
}

// ==================== DRAWING ====================

function buildFileToBlockMap(data) {
    fileToBlockMap.clear();
    if (!data || !data.blocks) return;
    Object.entries(data.blocks).forEach(([cat, sub]) => {
        Object.values(sub).forEach(files => files.forEach(f => fileToBlockMap.set(f.fileName, cat)));
    });
}

function redrawConnections() {
    if (!window.INITIAL_DATA || !connLayer) return;
    connLayer.innerHTML = "";
    dotsLayer.innerHTML = "";

    const connections = [];
    Object.entries(window.INITIAL_DATA.blocks).forEach(([srcCat, sub]) => {
        Object.values(sub).forEach(files => files.forEach(file => {
            file.classes?.forEach(cls => cls.methods?.forEach(m => m.calls?.forEach(call => {
                if (!call.targetLocation) return;
                const tgtCat = fileToBlockMap.get(call.targetLocation.split(" > ")[0]);
                if (tgtCat && tgtCat !== srcCat) {
                    connections.push({
                        src: srcCat, tgt: tgtCat, type: call.type,
                        label: `${file.fileName}:${m.name} -> ${call.targetLocation}`,
                        isBi: call.hasDataCallback || tgtCat === "Infrastructure" || tgtCat === "Data"
                    });
                }
            })));
        })));
    });

    const spacing = Math.max(0.5, 10 * scale);
    const groups = {};
    connections.forEach(c => {
        const key = `${c.src}_${c.tgt}_${c.type}`;
        if (!groups[key]) groups[key] = [];
        groups[key].push(c);
    });

    Object.values(groups).forEach(list => {
        const isData = list[0].type === "REQUEST";
        const busY = isData ? 520 : 80;
        list.forEach((item, i) => {
            const src = blockConfig[item.src];
            const tgt = blockConfig[item.tgt];
            const xOut = Math.min(src.x + (i * spacing), src.x + src.w/2 - 2);
            const xIn = Math.max(tgt.x + tgt.w - (i * spacing), tgt.x + tgt.w/2 + 2);
            const startY = isData ? src.y + src.h : src.y;
            const endY = isData ? tgt.y + tgt.h : tgt.y;

            const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
            const color = isData ? (item.isBi ? "#000080" : "#00bfff") : "#006400";
            const marker = isData ? (item.isBi ? "arrowInData" : "arrowInDataLight") : "arrowInCall";

            const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
            path.setAttribute("d", `M ${xOut} ${startY} L ${xOut} ${busY} L ${xIn} ${busY} L ${xIn} ${endY}`);
            path.setAttribute("stroke", color);
            path.setAttribute("fill", "none");
            path.setAttribute("marker-end", `url(#${marker})`);
            if (isData && item.isBi) path.setAttribute("stroke-width", "2");

            const hit = path.cloneNode();
            hit.setAttribute("stroke", "transparent");
            hit.setAttribute("stroke-width", "10");
            hit.style.pointerEvents = "stroke";
            hit.onmouseenter = (e) => {
                path.setAttribute("stroke", "#ff0000");
                path.setAttribute("stroke-width", "3");
                tooltip.innerText = item.label;
                tooltip.style.visibility = "visible";
                tooltip.style.left = (e.clientX + 10) + "px";
                tooltip.style.top = (e.clientY + 10) + "px";
            };
            hit.onmouseleave = () => {
                path.setAttribute("stroke", color);
                path.setAttribute("stroke-width", isData && item.isBi ? "2" : "1");
                tooltip.style.visibility = "hidden";
            };

            g.appendChild(path);
            g.appendChild(hit);
            connLayer.appendChild(g);
        });
    });

    if (statsPanel) statsPanel.innerText = `Total: ${connections.length} | Zoom: ${scale.toFixed(2)}x`;
}

function initVisualization(data) {
    if (!data) return;
    initDomElements();
    window.INITIAL_DATA = data;
    buildFileToBlockMap(data);
    updateArrowMarkers();
    redrawConnections();
}

// Запуск
window.initVisualization = initVisualization;
document.addEventListener('DOMContentLoaded', () => {
    if (window.INITIAL_DATA) initVisualization(window.INITIAL_DATA);
});

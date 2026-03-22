// ==================== visualization.js ====================
// Main visualization script for architecture dependencies

// ==================== GLOBAL VARIABLES ====================

window.INITIAL_DATA = null;
let isLoading = false;
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
let activeConnectionIds = [];

// ==================== DOM ELEMENTS ====================

const viewport = document.getElementById('viewport');
const connLayer = document.getElementById('connections-layer');
const dotsLayer = document.getElementById('dots-layer');
const tooltip = document.getElementById('tooltip');
const svg = document.getElementById('main-svg');
const container = document.getElementById('canvas-container');
const statsPanel = document.getElementById('stats-panel');

// ==================== ZOOM & PAN ====================

let scale = 1;
let tx = 0;
let ty = 0;
let isDragging = false;
let lastX, lastY;

function getCurrentLineSpacing() {
    let spacing = BASE_LINE_SPACING * scale;
    spacing = Math.max(0.5, Math.min(40, spacing));
    return spacing;
}

function createMarker(id, length, width, refX, refY, color) {
    const marker = document.createElementNS("http://www.w3.org/2000/svg", "marker");
    marker.setAttribute("id", id);
    marker.setAttribute("markerWidth", String(length));
    marker.setAttribute("markerHeight", String(width));
    marker.setAttribute("refX", String(refX));
    marker.setAttribute("refY", String(refY));
    marker.setAttribute("orient", "auto");
    marker.setAttribute("markerUnits", "strokeWidth");

    const polygon = document.createElementNS("http://www.w3.org/2000/svg", "polygon");
    polygon.setAttribute("points", `0 0, ${length - 2} ${width / 2}, 0 ${width}`);
    polygon.setAttribute("fill", color);
    polygon.setAttribute("stroke", "none");
    marker.appendChild(polygon);

    return marker;
}

function updateArrowMarkers() {
    let defs = svg.querySelector('defs');
    if (!defs) {
        defs = document.createElementNS("http://www.w3.org/2000/svg", "defs");
        svg.prepend(defs);
    }

    defs.innerHTML = '';

    const arrowLength = Math.min(40, Math.max(12, 20 * scale));
    const arrowWidth = arrowLength / 2.5;
    const refX = arrowLength - 2;
    const refY = arrowWidth / 2;

    const colors = {
        outCall: "#008000",
        outData: "#000080",
        outDataLight: "#00bfff",
        inCall: "#ffd700",
        inData: "#000080",
        inDataLight: "#00bfff"
    };

    const markerOutCall = createMarker("arrowOutCall", arrowLength, arrowWidth, refX, refY, colors.outCall);
    defs.appendChild(markerOutCall);

    const markerOutData = createMarker("arrowOutData", arrowLength, arrowWidth, refX, refY, colors.outData);
    defs.appendChild(markerOutData);

    const markerOutDataLight = createMarker("arrowOutDataLight", arrowLength, arrowWidth, refX, refY, colors.outDataLight);
    defs.appendChild(markerOutDataLight);

    const markerInCall = createMarker("arrowInCall", arrowLength, arrowWidth, refX, refY, colors.inCall);
    defs.appendChild(markerInCall);

    const markerInData = createMarker("arrowInData", arrowLength, arrowWidth, refX, refY, colors.inData);
    defs.appendChild(markerInData);

    const markerInDataLight = createMarker("arrowInDataLight", arrowLength, arrowWidth, refX, refY, colors.inDataLight);
    defs.appendChild(markerInDataLight);

    const markerInDataRed = createMarker("arrowInDataRed", arrowLength, arrowWidth, refX, refY, "#ff0000");
    defs.appendChild(markerInDataRed);
}

container.onwheel = (e) => {
    e.preventDefault();
    const delta = e.deltaY > 0 ? 0.9 : 1.1;

    const pt = svg.createSVGPoint();
    pt.x = e.clientX;
    pt.y = e.clientY;
    const svgP = pt.matrixTransform(svg.getScreenCTM().inverse());

    tx = svgP.x - (svgP.x - tx) * delta;
    ty = svgP.y - (svgP.y - ty) * delta;
    scale *= delta;
    scale = Math.max(0.2, Math.min(5, scale));

    updateTransform();
    updateArrowMarkers();
    redrawConnections();
};

container.onmousedown = (e) => {
    if(e.button === 0) {
        isDragging = true;
        lastX = e.clientX;
        lastY = e.clientY;
    }
};

window.onmousemove = (e) => {
    if (!isDragging) return;
    const dx = e.clientX - lastX;
    const dy = e.clientY - lastY;
    tx += dx;
    ty += dy;
    lastX = e.clientX;
    lastY = e.clientY;
    updateTransform();
};

window.onmouseup = () => {
    isDragging = false;
};

function updateTransform() {
    viewport.setAttribute('transform', `translate(${tx},${ty}) scale(${scale})`);
}

// ==================== FILE MAP ====================

function buildFileToBlockMap(data) {
    fileToBlockMap.clear();
    if (!data.blocks) return;

    Object.entries(data.blocks).forEach(([blockName, subBlocks]) => {
        if (!subBlocks || typeof subBlocks !== 'object') return;

        Object.values(subBlocks).forEach(files => {
            if (!Array.isArray(files)) return;

            files.forEach(file => {
                if (file.fileName) {
                    fileToBlockMap.set(file.fileName, blockName);
                }
            });
        });
    });
}

function findBlockByFileName(fileName) {
    return fileToBlockMap.get(fileName) || null;
}

// ==================== HELPER FUNCTIONS ====================

function findMethodById(targetId, data) {
    if (!targetId) return null;

    for (const [blockName, subBlocks] of Object.entries(data.blocks)) {
        if (!subBlocks || typeof subBlocks !== 'object') continue;

        for (const files of Object.values(subBlocks)) {
            if (!Array.isArray(files)) continue;

            for (const file of files) {
                if (!file.classes) continue;

                for (const cls of file.classes) {
                    if (!cls.methods) continue;

                    for (const method of cls.methods) {
                        if (method.id === targetId) {
                            return method;
                        }
                    }
                }
            }
        }
    }
    return null;
}

function getBlockCategoryForMethod(targetId, data) {
    if (!targetId) return null;

    for (const [blockName, subBlocks] of Object.entries(data.blocks)) {
        if (!subBlocks || typeof subBlocks !== 'object') continue;

        for (const files of Object.values(subBlocks)) {
            if (!Array.isArray(files)) continue;

            for (const file of files) {
                if (!file.classes) continue;

                for (const cls of file.classes) {
                    if (!cls.methods) continue;

                    for (const method of cls.methods) {
                        if (method.id === targetId) {
                            return blockName;
                        }
                    }
                }
            }
        }
    }

    const classNameMatch = targetId.match(/^([A-Za-z0-9_]+)\(/);
    if (classNameMatch) {
        const className = classNameMatch[1];
        for (const [blockName, subBlocks] of Object.entries(data.blocks)) {
            if (!subBlocks || typeof subBlocks !== 'object') continue;

            for (const files of Object.values(subBlocks)) {
                if (!Array.isArray(files)) continue;

                for (const file of files) {
                    if (!file.classes) continue;

                    for (const cls of file.classes) {
                        if (cls.name === className) {
                            return blockName;
                        }
                    }
                }
            }
        }
    }

    for (const [blockName, subBlocks] of Object.entries(data.blocks)) {
        if (!subBlocks || typeof subBlocks !== 'object') continue;

        for (const files of Object.values(subBlocks)) {
            if (!Array.isArray(files)) continue;

            for (const file of files) {
                if (!file.classes) continue;

                for (const cls of file.classes) {
                    if (!cls.methods) continue;

                    for (const method of cls.methods) {
                        if (method.id.includes(targetId) || targetId.includes(method.id)) {
                            return blockName;
                        }
                    }
                }
            }
        }
    }

    return null;
}

function isMethodDataProvider(targetId, data) {
    if (!targetId) return false;

    const targetBlockCategory = getBlockCategoryForMethod(targetId, data);
    if (targetBlockCategory === "Infrastructure" || targetBlockCategory === "Data") {
        return true;
    }

    const method = findMethodById(targetId, data);
    if (method && method.calls) {
        for (const call of method.calls) {
            if (call.type === "REQUEST" && call.hasDataCallback === true) {
                return true;
            }
        }
    }

    return false;
}

function extractClassNameFromId(methodId) {
    if (!methodId) return null;

    const patterns = [
        /([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)\(/,
        /([A-Za-z0-9_]+)\(/,
        /\.([A-Za-z0-9_]+)\(/
    ];

    for (const pattern of patterns) {
        const match = methodId.match(pattern);
        if (match) {
            return match[1] || match[0].replace(/[\(\.]/g, '');
        }
    }

    const parts = methodId.split('.');
    if (parts.length > 0) {
        const lastPart = parts[parts.length - 1];
        return lastPart.split('(')[0];
    }

    return null;
}

function findRequestLineByActionTargetId(actionTargetId, data) {
    if (!actionTargetId) return null;

    const actionClassName = extractClassNameFromId(actionTargetId);

    const requestLines = document.querySelectorAll('.conn-group[data-is-data="true"]');

    for (const line of requestLines) {
        const lineTargetId = line.getAttribute("data-target-id");
        if (!lineTargetId) continue;

        if (lineTargetId === actionTargetId) {
            return line;
        }

        const requestClassName = extractClassNameFromId(lineTargetId);

        if (actionClassName && requestClassName && actionClassName === requestClassName) {
            return line;
        }
    }

    return null;
}

function findActionLineByRequestTargetId(requestTargetId, data) {
    if (!requestTargetId) return null;

    const requestClassName = extractClassNameFromId(requestTargetId);

    const actionLines = document.querySelectorAll('.conn-group[data-is-data="false"]');

    for (const line of actionLines) {
        const lineTargetId = line.getAttribute("data-target-id");
        if (!lineTargetId) continue;

        if (lineTargetId === requestTargetId) {
            return line;
        }

        const actionClassName = extractClassNameFromId(lineTargetId);

        if (requestClassName && actionClassName && requestClassName === actionClassName) {
            return line;
        }
    }

    return null;
}

// ==================== DRAWING FUNCTIONS ====================

function createSeg(d, cls, markerEnd = null, strokeWidth = 1) {
    const p = document.createElementNS("http://www.w3.org/2000/svg", "path");
    p.setAttribute("d", d);
    p.setAttribute("class", "conn-segment " + cls);
    if (markerEnd) {
        p.setAttribute("marker-end", `url(#${markerEnd})`);
    }
    if (strokeWidth !== 1) {
        p.setAttribute("stroke-width", String(strokeWidth));
    }
    return p;
}

function createDot(x, y, txt, color) {
    const dot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    dot.setAttribute("cx", x);
    dot.setAttribute("cy", y);
    dot.setAttribute("class", "dot");
    dot.setAttribute("r", "3");
    dot.setAttribute("fill", color);

    dot.onmouseenter = (e) => {
        dot.setAttribute("fill", "#ff0000");
        showTooltip(e, txt);
        e.stopPropagation();
    };

    dot.onmouseleave = () => {
        dot.setAttribute("fill", color);
        tooltip.style.visibility = "hidden";
    };

    dotsLayer.appendChild(dot);
}

function showTooltip(e, txt) {
    tooltip.innerText = txt;
    tooltip.style.visibility = "visible";
    tooltip.style.left = (e.clientX + 15) + "px";
    tooltip.style.top = (e.clientY + 15) + "px";
}

function renderBidirectionalPath(srcId, tgtId, busY, items, index = 0, total = 1) {
    const src = blockConfig[srcId];
    const tgt = blockConfig[tgtId];
    if (!src || !tgt) return null;

    const lineSpacing = getCurrentLineSpacing();
    const step = total > 1 ? lineSpacing : 0;

    const centerX_src = src.x + src.w / 2;
    const centerX_tgt = tgt.x + tgt.w / 2;

    const xOut = Math.min(src.x + (index * step), centerX_src - 2);
    const xIn = Math.max(tgt.x + tgt.w - (index * step), centerX_tgt + 2);

    const startY = src.y + src.h;
    const endY = tgt.y + tgt.h;

    const connectionId = `${srcId}_${tgtId}_bi_${index}`;

    const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
    g.setAttribute("data-connection-id", connectionId);
    g.setAttribute("data-src-id", srcId);
    g.setAttribute("data-tgt-id", tgtId);
    g.setAttribute("data-bidirectional", "true");
    g.setAttribute("data-is-data", "true");
    g.setAttribute("data-index", index);
    g.setAttribute("class", "conn-group bidirectional");

    const gradientId = `gradient_${Date.now()}_${Math.random()}_${index}`;

    const gradient = document.createElementNS("http://www.w3.org/2000/svg", "linearGradient");
    gradient.setAttribute("id", gradientId);
    gradient.setAttribute("gradientUnits", "objectBoundingBox");
    gradient.setAttribute("x1", "0%");
    gradient.setAttribute("y1", "0%");
    gradient.setAttribute("x2", "100%");
    gradient.setAttribute("y2", "0%");

    const stop1 = document.createElementNS("http://www.w3.org/2000/svg", "stop");
    stop1.setAttribute("offset", "0%");
    stop1.setAttribute("stop-color", "#000080");
    gradient.appendChild(stop1);

    const stop2 = document.createElementNS("http://www.w3.org/2000/svg", "stop");
    stop2.setAttribute("offset", "100%");
    stop2.setAttribute("stop-color", "#000080");
    gradient.appendChild(stop2);

    let defs = svg.querySelector('defs');
    if (!defs) {
        defs = document.createElementNS("http://www.w3.org/2000/svg", "defs");
        svg.prepend(defs);
    }
    defs.appendChild(gradient);

    const fullPathD = `M ${xOut} ${startY} L ${xOut} ${busY} L ${xIn} ${busY} L ${xIn} ${endY}`;

    const mainPath = createSeg(fullPathD, "line-bidirectional", null, 2);
    mainPath.setAttribute("stroke", `url(#${gradientId})`);

    const arrowAtSrc = document.createElementNS("http://www.w3.org/2000/svg", "path");
    arrowAtSrc.setAttribute("d", `M ${xOut} ${busY} L ${xOut} ${startY}`);
    arrowAtSrc.setAttribute("class", "conn-segment arrow-src");
    arrowAtSrc.setAttribute("marker-end", "url(#arrowInData)");
    arrowAtSrc.setAttribute("stroke", "none");
    arrowAtSrc.setAttribute("fill", "none");

    const arrowAtTgt = document.createElementNS("http://www.w3.org/2000/svg", "path");
    arrowAtTgt.setAttribute("d", `M ${xIn} ${busY} L ${xIn} ${endY}`);
    arrowAtTgt.setAttribute("class", "conn-segment arrow-tgt");
    arrowAtTgt.setAttribute("marker-end", "url(#arrowInData)");
    arrowAtTgt.setAttribute("stroke", "none");
    arrowAtTgt.setAttribute("fill", "none");

    const hitArea = document.createElementNS("http://www.w3.org/2000/svg", "path");
    hitArea.setAttribute("d", fullPathD);
    hitArea.setAttribute("class", "hit-area");

    g.appendChild(mainPath);
    g.appendChild(arrowAtSrc);
    g.appendChild(arrowAtTgt);
    g.appendChild(hitArea);

    const txt = items.length > 1 ? (items.length + " connections") : (items[0]?.label || "bidirectional");

    if (items[0]) {
        g.setAttribute("data-target-id", items[0].targetId || "");
        g.setAttribute("data-type", items[0].type || "");
    }

    g.onmouseenter = (e) => {
        g.classList.add('hover-highlight');
    };

    g.onmouseleave = () => {
        tooltip.style.visibility = "hidden";
        g.classList.remove('hover-highlight');
    };

    g.ondblclick = (e) => {
        e.stopPropagation();

        const highlightedLines = document.querySelectorAll('.conn-group.hover-highlight');

        if (highlightedLines.length > 0) {
            document.querySelectorAll('.conn-group.active').forEach(el => {
                el.classList.remove('active');
            });
            activeConnectionIds = [];

            highlightedLines.forEach(line => {
                line.classList.add('active');
                const id = line.getAttribute("data-connection-id");
                if (id) activeConnectionIds.push(id);
            });

            document.body.classList.add('has-active');
        } else {
            if (g.classList.contains('active')) {
                g.classList.remove('active');
                activeConnectionIds = activeConnectionIds.filter(id => id !== connectionId);
                if (activeConnectionIds.length === 0) {
                    document.body.classList.remove('has-active');
                }
            } else {
                document.querySelectorAll('.conn-group.active').forEach(el => {
                    el.classList.remove('active');
                });
                activeConnectionIds = [];

                g.classList.add('active');
                activeConnectionIds.push(connectionId);
                document.body.classList.add('has-active');
            }
        }
    };

    g.oncontextmenu = (e) => {
        e.preventDefault();
        e.stopPropagation();

        document.querySelectorAll('.conn-group.active').forEach(el => {
            el.classList.remove('active');
        });
        document.querySelectorAll('.conn-group.hover-highlight').forEach(el => {
            el.classList.remove('hover-highlight');
        });
        activeConnectionIds = [];
        document.body.classList.remove('has-active');
        return false;
    };

    connLayer.appendChild(g);

    createDot(xOut, startY, txt, "#000080");
    createDot(xIn, endY, txt, "#000080");

    return g;
}

function renderPath(srcId, tgtId, busY, isData, cls, items, merged, index = 0, total = 1, isBidirectional = true) {
    const src = blockConfig[srcId];
    const tgt = blockConfig[tgtId];
    if (!src || !tgt) return null;

    if (isData && isBidirectional) {
        return renderBidirectionalPath(srcId, tgtId, busY, items, index, total);
    }

    const centerX_src = src.x + src.w / 2;
    const centerX_tgt = tgt.x + tgt.w / 2;

    const lineSpacing = getCurrentLineSpacing();
    const step = total > 1 ? lineSpacing : 0;

    const xOut = Math.min(src.x + (index * step), centerX_src - 2);
    const xIn = Math.max(tgt.x + tgt.w - (index * step), centerX_tgt + 2);

    const startY = isData ? (src.y + src.h) : src.y;
    const endY = isData ? (tgt.y + tgt.h) : tgt.y;

    const g = document.createElementNS("http://www.w3.org/2000/svg", "g");

    const connectionId = srcId + "_" + tgtId + "_" + isData + "_" + index + "_" + (isBidirectional ? "bi" : "uni");
    g.setAttribute("data-connection-id", connectionId);
    g.setAttribute("data-src-id", srcId);
    g.setAttribute("data-tgt-id", tgtId);
    g.setAttribute("data-is-data", isData);
    g.setAttribute("data-index", index);
    g.setAttribute("data-bidirectional", isBidirectional);
    g.setAttribute("class", "conn-group " + cls);

    const outColorClass = isData ? "line-out-data" : "line-out-call";
    const inColorClass = isData ? "line-in-data" : "line-in-call";

    let outMarker, inMarker;
    let lineStrokeWidth = 1;
    let lineColor;

    if (isData) {
        if (isBidirectional) {
            outMarker = null;
            inMarker = "arrowInData";
            lineStrokeWidth = 2;
            lineColor = "#000080";
        } else {
            const reversedSrc = blockConfig[tgtId];
            const reversedTgt = blockConfig[srcId];

            if (reversedSrc && reversedTgt) {
                const revCenterX_src = reversedSrc.x + reversedSrc.w / 2;
                const revCenterX_tgt = reversedTgt.x + reversedTgt.w / 2;

                const revXOut = Math.min(reversedSrc.x + (index * step), revCenterX_src - 2);
                const revXIn = Math.max(reversedTgt.x + reversedTgt.w - (index * step), revCenterX_tgt + 2);

                const revStartY = isData ? (reversedSrc.y + reversedSrc.h) : reversedSrc.y;
                const revEndY = isData ? (reversedTgt.y + reversedTgt.h) : reversedTgt.y;

                outMarker = null;
                inMarker = "arrowInDataLight";
                lineStrokeWidth = 1;
                lineColor = "#00bfff";

                const outPathRev = createSeg(`M ${revXOut} ${revStartY} L ${revXOut} ${busY}`, outColorClass, outMarker, lineStrokeWidth);
                const busPathRev = createSeg(`M ${revXOut} ${busY} L ${revXIn} ${busY}`, "line-bus-core", null, lineStrokeWidth);
                busPathRev.setAttribute("stroke", lineColor);
                const inPathRev = createSeg(`M ${revXIn} ${busY} L ${revXIn} ${revEndY}`, inColorClass, inMarker, lineStrokeWidth);

                const hitAreaRev = document.createElementNS("http://www.w3.org/2000/svg", "path");
                hitAreaRev.setAttribute("d", `M ${revXOut} ${revStartY} L ${revXOut} ${busY} L ${revXIn} ${busY} L ${revXIn} ${revEndY}`);
                hitAreaRev.setAttribute("class", "hit-area");

                g.append(outPathRev, busPathRev, inPathRev, hitAreaRev);

                const txt = merged ? (items.length + " connections") : items[0].label;
                if (!merged && items[0]) {
                    g.setAttribute("data-target-id", items[0].targetId || "");
                    g.setAttribute("data-type", items[0].type || "");
                }

                const outDotColor = "#00bfff";
                const inDotColor = "#00bfff";
                createDot(revXOut, revStartY, txt, outDotColor);
                createDot(revXIn, revEndY, txt, inDotColor);

                g.onmouseenter = (e) => { g.classList.add('hover-highlight'); };
                g.onmouseleave = () => { tooltip.style.visibility = "hidden"; document.querySelectorAll('.hover-highlight').forEach(el => el.classList.remove('hover-highlight')); };
                g.ondblclick = (e) => { e.stopPropagation(); g.classList.toggle('active'); };
                g.oncontextmenu = (e) => { e.preventDefault(); g.classList.remove('active'); return false; };

                connLayer.appendChild(g);
                return g;
            }
        }
    } else {
        outMarker = null;
        inMarker = "arrowInCall";
        lineColor = "#006400";
    }

    const outPath = createSeg(`M ${xOut} ${startY} L ${xOut} ${busY}`, outColorClass, outMarker, lineStrokeWidth);
    const busPath = createSeg(`M ${xOut} ${busY} L ${xIn} ${busY}`, "line-bus-core", null, lineStrokeWidth);
    busPath.setAttribute("stroke", lineColor);
    const inPath = createSeg(`M ${xIn} ${busY} L ${xIn} ${endY}`, inColorClass, inMarker, lineStrokeWidth);

    const hitArea = document.createElementNS("http://www.w3.org/2000/svg", "path");
    hitArea.setAttribute("d", `M ${xOut} ${startY} L ${xOut} ${busY} L ${xIn} ${busY} L ${xIn} ${endY}`);
    hitArea.setAttribute("class", "hit-area");

    g.append(outPath, busPath, inPath, hitArea);

    const txt = merged ? (items.length + " connections") : items[0].label;

    if (!merged && items[0]) {
        g.setAttribute("data-target-id", items[0].targetId || "");
        g.setAttribute("data-type", items[0].type || "");
    }

    g.onmouseenter = (e) => {
        if (merged) {
            g.style.display = "none";
            items.forEach((it, i) => {
                const sub = renderPath(srcId, tgtId, busY, isData, "", [it], false, i, items.length, isBidirectional);
                if (sub) {
                    sub.classList.add('active-expansion');
                    connLayer.appendChild(sub);
                }
            });
        } else {
            const targetId = g.getAttribute("data-target-id");
            const isDataProvider = targetId && isMethodDataProvider(targetId, window.INITIAL_DATA);

            g.classList.add('hover-highlight');

            if (!isData && isDataProvider) {
                const requestLine = findRequestLineByActionTargetId(targetId, window.INITIAL_DATA);
                if (requestLine) {
                    requestLine.classList.add('hover-highlight');
                }
            }

            if (isData && isDataProvider) {
                const actionLine = findActionLineByRequestTargetId(targetId, window.INITIAL_DATA);
                if (actionLine) {
                    actionLine.classList.add('hover-highlight');
                }
            }
        }
    };

    g.onmouseleave = () => {
        tooltip.style.visibility = "hidden";
        if (merged) {
            document.querySelectorAll('.active-expansion').forEach(el => el.remove());
            g.style.display = "inline";
        } else {
            document.querySelectorAll('.hover-highlight').forEach(el => {
                el.classList.remove('hover-highlight');
            });
        }
    };

    g.ondblclick = (e) => {
        e.stopPropagation();

        const highlightedLines = document.querySelectorAll('.conn-group.hover-highlight');

        if (highlightedLines.length > 0) {
            document.querySelectorAll('.conn-group.active').forEach(el => {
                el.classList.remove('active');
            });
            activeConnectionIds = [];

            highlightedLines.forEach(line => {
                line.classList.add('active');
                const id = line.getAttribute("data-connection-id");
                if (id) activeConnectionIds.push(id);
            });

            document.body.classList.add('has-active');
        } else {
            if (g.classList.contains('active')) {
                g.classList.remove('active');
                activeConnectionIds = activeConnectionIds.filter(id => id !== connectionId);
                if (activeConnectionIds.length === 0) {
                    document.body.classList.remove('has-active');
                }
            } else {
                document.querySelectorAll('.conn-group.active').forEach(el => {
                    el.classList.remove('active');
                });
                activeConnectionIds = [];

                g.classList.add('active');
                activeConnectionIds.push(connectionId);
                document.body.classList.add('has-active');
            }
        }
    };

    g.oncontextmenu = (e) => {
        e.preventDefault();
        e.stopPropagation();

        document.querySelectorAll('.conn-group.active').forEach(el => {
            el.classList.remove('active');
        });
        document.querySelectorAll('.conn-group.hover-highlight').forEach(el => {
            el.classList.remove('hover-highlight');
        });
        activeConnectionIds = [];
        document.body.classList.remove('has-active');
        return false;
    };

    connLayer.appendChild(g);

    const outDotColor = isData ? (isBidirectional ? "#000080" : "#00bfff") : "#008000";
    const inDotColor = isData ? (isBidirectional ? "#000080" : "#00bfff") : "#ffd700";

    if (!merged) {
        createDot(xOut, startY, txt, outDotColor);
        createDot(xIn, endY, txt, inDotColor);
    }
    return g;
}

function redrawConnections() {
    if (!INITIAL_DATA || !INITIAL_DATA.blocks) {
        return;
    }

    const currentData = window.INITIAL_DATA;
    connLayer.innerHTML = "";
    dotsLayer.innerHTML = "";

    const busCallY = 80;
    const busDataY = 520;
    const connections = [];

    Object.entries(currentData.blocks).forEach(([srcCat, subBlocks]) => {
        if (!subBlocks || typeof subBlocks !== 'object') return;

        Object.values(subBlocks).forEach(files => {
            if (!Array.isArray(files)) return;

            files.forEach(file => {
                if (!file.classes) return;

                file.classes.forEach(cls => {
                    if (!cls.methods) return;

                    cls.methods.forEach(m => {
                        if (!m.calls) return;

                        m.calls.forEach(call => {
                            if (!call.targetId || call.targetId === "") return;

                            let targetFileName = null;
                            if (call.targetLocation) {
                                const parts = call.targetLocation.split(" > ");
                                targetFileName = parts[0];
                            }

                            let targetCat = null;
                            if (targetFileName) {
                                targetCat = findBlockByFileName(targetFileName);
                            }

                            if (targetCat && targetCat !== srcCat) {
                                const tooltipText = file.fileName + " : " + m.name + " -> " + call.targetLocation;

                                connections.push({
                                    src: srcCat,
                                    tgt: targetCat,
                                    type: call.type || "REQUEST",
                                    targetId: call.targetId,
                                    label: tooltipText,
                                    methodName: m.name,
                                    hasDataCallback: call.hasDataCallback || false
                                });
                            }
                        });
                    });
                });
            });
        });
    });

    const actions = [];
    const allRequests = [];

    connections.forEach(c => {
        if (c.type === "ACTION") {
            actions.push(c);
        } else if (c.type === "REQUEST") {
            const targetBlockCategory = getBlockCategoryForMethod(c.targetId, currentData);
            const isFromDataProvider = targetBlockCategory === "Infrastructure" || targetBlockCategory === "Data";
            const isBidirectional = c.hasDataCallback === true || isFromDataProvider;

            allRequests.push({
                ...c,
                isBidirectional: isBidirectional
            });
        }
    });

    const actionGroups = {};
    actions.forEach(c => {
        const key = c.src + "_" + c.tgt;
        if (!actionGroups[key]) actionGroups[key] = [];
        actionGroups[key].push(c);
    });

    Object.entries(actionGroups).forEach(([key, list]) => {
        const count = list.length;
        list.forEach((item, i) => {
            renderPath(item.src, item.tgt, busCallY, false, "", [item], false, i, count, false);
        });
    });

    const requestGroups = {};
    allRequests.forEach(c => {
        const key = c.src + "_" + c.tgt;
        if (!requestGroups[key]) requestGroups[key] = [];
        requestGroups[key].push(c);
    });

    Object.entries(requestGroups).forEach(([key, list]) => {
        const count = list.length;
        list.forEach((item, i) => {
            renderPath(item.src, item.tgt, busDataY, true, "", [item], false, i, count, item.isBidirectional);
        });
    });

    if (activeConnectionIds.length > 0) {
        activeConnectionIds.forEach(id => {
            const restoredLine = document.querySelector(`.conn-group[data-connection-id="${id}"]`);
            if (restoredLine) {
                restoredLine.classList.add('active');
            }
        });
        document.body.classList.add('has-active');
    }

    const actionCount = actions.length;
    const requestCount = allRequests.length;
    const bidirectionalCount = allRequests.filter(r => r.isBidirectional).length;
    const unidirectionalCount = requestCount - bidirectionalCount;

    if (statsPanel) {
        statsPanel.innerHTML = 'Total: ' + connections.length + ' | ACTION: ' + actionCount + ' | REQUEST: ' + requestCount + ' (Bidirectional: ' + bidirectionalCount + ', Unidirectional: ' + unidirectionalCount + ') | Zoom: ' + scale.toFixed(2) + 'x | Spacing: ' + getCurrentLineSpacing().toFixed(2) + 'px<br><span style="font-size:10px;opacity:0.7;">Double click to highlight | Right click to clear all</span>';
    }
}

function draw() {
    if (!INITIAL_DATA || !INITIAL_DATA.blocks) {
        return;
    }

    updateArrowMarkers();
    redrawConnections();
}

function showError(message) {
    const errorDiv = document.createElement('div');
    errorDiv.className = 'error-message';
    errorDiv.textContent = message;
    document.body.appendChild(errorDiv);
    setTimeout(() => errorDiv.remove(), 5000);
}

function showLoading() {
    if (document.getElementById('loading-overlay')) return;
    const loadingDiv = document.createElement('div');
    loadingDiv.id = 'loading-overlay';
    loadingDiv.className = 'loading-overlay';
    loadingDiv.innerHTML = '<div>Loading data...</div>';
    document.body.appendChild(loadingDiv);
}

function hideLoading() {
    const loadingDiv = document.getElementById('loading-overlay');
    if (loadingDiv) loadingDiv.remove();
}

function initVisualizationWithData(jsonData) {
    if (!jsonData) {
        hideLoading();
        showError('Failed to load data');
        return;
    }

    if (!jsonData.blocks) {
        hideLoading();
        showError('Invalid JSON structure: missing "blocks" field');
        return;
    }

    buildFileToBlockMap(jsonData);
    window.INITIAL_DATA = jsonData;
    draw();
    hideLoading();
}

window.initVisualizationWithData = initVisualizationWithData;
window.showLoading = showLoading;
window.hideLoading = hideLoading;
window.showError = showError;
window.redrawConnections = redrawConnections;

document.addEventListener('DOMContentLoaded', () => {
    updateArrowMarkers();
});
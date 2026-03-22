// ==================== loader.js ====================
// Data loading script

async function loadJSONFromPath(path) {
    try {
        console.log('Loading from:', path);
        const response = await fetch(path);

        if (!response.ok) {
            throw new Error('HTTP error: ' + response.status);
        }

        const data = await response.json();
        console.log('JSON loaded successfully');
        return data;
    } catch (error) {
        console.error('Error loading JSON:', error);
        return null;
    }
}

async function loadCodemapFromRoot() {
    return await loadJSONFromPath('./codemap_export.json');
}

async function loadJSONFromFile(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();

        reader.onload = (e) => {
            try {
                const data = JSON.parse(e.target.result);
                console.log('File loaded successfully');
                resolve(data);
            } catch (error) {
                console.error('Error parsing JSON:', error);
                reject(error);
            }
        };

        reader.onerror = (e) => {
            console.error('Error reading file:', e);
            reject(new Error('Error reading file'));
        };

        reader.readAsText(file);
    });
}

async function loadAndInitialize() {
    if (window.showLoading) window.showLoading();

    const jsonData = await loadCodemapFromRoot();

    if (!jsonData) {
        if (window.hideLoading) window.hideLoading();
        if (window.showError) window.showError('Could not load codemap_export.json');
        return;
    }

    if (window.initVisualizationWithData) {
        window.initVisualizationWithData(jsonData);
    } else {
        console.error('Visualizer not found');
        if (window.hideLoading) window.hideLoading();
    }
}

function reloadVisualization() {
    console.log('Reloading visualization...');
    loadAndInitialize();
}

function loadFromFileDialog() {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json';

    input.onchange = async (e) => {
        const file = e.target.files[0];
        if (!file) return;

        if (window.showLoading) window.showLoading();

        try {
            const jsonData = await loadJSONFromFile(file);
            if (jsonData && window.initVisualizationWithData) {
                window.initVisualizationWithData(jsonData);
            } else if (window.showError) {
                window.showError('Could not load file');
            }
        } catch (error) {
            if (window.showError) {
                window.showError('Error: ' + error.message);
            }
        }
    };

    input.click();
}

window.loadAndInitialize = loadAndInitialize;
window.reloadVisualization = reloadVisualization;
window.loadFromFileDialog = loadFromFileDialog;

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        loadAndInitialize();
    });
} else {
    loadAndInitialize();
}
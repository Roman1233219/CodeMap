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

// Попытка загрузить файл из корня (если он там есть)
async function loadCodemapFromRoot() {
    // В JCEF относительный путь может не сработать без настройки,
    // поэтому мы полагаемся на initVisualizationWithData из плагина.
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

// Основная функция инициализации (теперь вызывается плагином напрямую)
function initVisualizationWithData(jsonData) {
    console.log('initVisualizationWithData called with:', jsonData);
    if (!jsonData) {
        if (window.hideLoading) window.hideLoading();
        if (window.showError) window.showError('Data is empty');
        return;
    }

    if (window.initVisualizationWithDataFromScript) {
        window.initVisualizationWithDataFromScript(jsonData);
    } else {
        // Если визуализатор еще не загружен или функция переименована
        console.error('Visualizer function not found');
    }
}

function reloadVisualization() {
    // В данной архитектуре перезагрузка инициируется кнопкой в плагине
    console.log('Please use "Show Visualization" button in the plugin to reload.');
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

// Экспортируем функции в глобальную область
window.initVisualizationWithData = initVisualizationWithData;
window.reloadVisualization = reloadVisualization;
window.loadFromFileDialog = loadFromFileDialog;

console.log('Loader script ready');

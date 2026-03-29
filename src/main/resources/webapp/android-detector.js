// android-detector.js - обновленная версия

class AndroidComponentDetector {
    constructor(psiData) {
        this.psiData = psiData;
        this.components = {
            activities: [],
            fragments: [],
            services: [],
            receivers: [],
            viewModels: [],
            repositories: [],
            daos: [],
            entities: []
        };
        this.intentLinks = [];
        this.broadcastLinks = [];

        if (psiData && psiData.files) {
            this.detectAll();

            if (psiData.intentLinks) {
                this.intentLinks = psiData.intentLinks;
                console.log(`🔗 Загружено ${this.intentLinks.length} Intent связей из JSON`);
            }

            if (psiData.broadcastLinks) {
                this.broadcastLinks = psiData.broadcastLinks;
                console.log(`📡 Загружено ${this.broadcastLinks.length} Broadcast связей из JSON`);
            }
        }

        window.androidDetector = this;
    }

    detectAll() {
        if (!this.psiData || !this.psiData.files) return false;

        for (const file of this.psiData.files) {
            this.detectInFile(file);
        }

        console.log('✅ Android компоненты найдены:', {
            activities: this.components.activities.length,
            services: this.components.services.length,
            receivers: this.components.receivers.length,
            viewModels: this.components.viewModels.length,
            daos: this.components.daos.length
        });

        return true;
    }

    detectInFile(file) {
        const fileName = file.fileName;
        const packageName = file.packageName || '';

        if (!file.functions) return;

        for (const func of file.functions) {
            const componentType = this.detectComponentType(fileName, packageName, func);

            if (componentType && this.components[componentType]) {
                this.components[componentType].push({
                    id: func.id,
                    name: func.name,
                    fileName: fileName,
                    packageName: packageName,
                    className: fileName.replace('.kt', '').replace('.java', ''),
                    isEntryPoint: func.isEntryPoint || false,
                    calls: func.calls || [],
                    inboundCalls: func.inboundCalls || [],
                    type: func.type,
                    signature: func.signature || ''
                });
            }
        }
    }

    detectComponentType(fileName, packageName, func) {
        const className = fileName.replace('.kt', '').replace('.java', '');

        if (className.endsWith('Activity') && func.name === 'onCreate') return 'activities';
        if (className.endsWith('Fragment') && (func.name === 'onCreateView' || func.name === 'onViewCreated')) return 'fragments';
        if (className.endsWith('Service') && (func.name === 'onStartCommand' || func.name === 'onBind')) return 'services';
        if (func.name === 'onReceive') return 'receivers';
        if (className.endsWith('ViewModel')) return 'viewModels';
        if (className.endsWith('Repository') || packageName.includes('.repository')) return 'repositories';
        if (className.endsWith('Dao') || this.hasDaoAnnotation(func)) return 'daos';
        if (className.startsWith('Db') && !className.endsWith('Dao') && !className.endsWith('Database')) return 'entities';

        return null;
    }

    hasDaoAnnotation(func) {
        if (!func.signature) return false;
        const sig = func.signature;
        return sig.includes('@Dao') || sig.includes('@Query') || sig.includes('@Insert');
    }

    isSystemCallback(func) {
        const systemCallbacks = [
            'onResume', 'onPause', 'onDestroy', 'onStart', 'onStop',
            'onSaveInstanceState', 'onRestoreInstanceState', 'onRestart',
            'onCreateView', 'onViewCreated', 'onDestroyView',
            'onStartCommand', 'onBind', 'onUnbind', 'onRebind',
            'onReceive',
            'onLongPress', 'onTouchEvent', 'onInterceptTouchEvent',
            'onRequestDisallowInterceptTouchEvent', 'onSingleTapUp',
            'onDown', 'onFling', 'onScroll', 'onShowPress',
            'onCreateViewHolder', 'onBindViewHolder', 'onViewAttachedToWindow',
            'onViewDetachedFromWindow', 'onAttachedToRecyclerView', 'onDetachedFromRecyclerView',
            'onClick', 'onLongClick', 'onItemClick', 'onItemLongClick',
            'onCreate', 'onStart', 'onStop'
        ];

        if (systemCallbacks.includes(func.name)) return true;
        if (func.signature && func.signature.includes('@Override')) return true;
        if (func.isEntryPoint) return true;

        return false;
    }

    getSmartLinks(funcId) {
        const links = [];
        for (const link of this.intentLinks) {
            if (link.from === funcId) {
                links.push({ type: 'intent', target: link.to, targetType: link.type, lineNumber: link.lineNumber });
            }
        }
        return links;
    }
}

window.initAndroidDetector = function(psiData) {
    return new AndroidComponentDetector(psiData);
};
console.log('✅ android-detector.js загружен');

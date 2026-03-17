package com.example.codemap

import com.example.codemap.CodeMap.data.CodeMapCore
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class HelloAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        // Получаем проект из события
        val project = e.project ?: return
        
        // Создаем и запускаем обновление базы данных
        val core = CodeMapCore(project)
        core.refreshDatabase {
            Messages.showInfoMessage(
                project,
                "База данных CodeMap успешно обновлена!",
                "CodeMap Status"
            )
        }
    }
}

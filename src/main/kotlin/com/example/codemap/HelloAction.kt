package com.example.codemap

import com.example.codemap.CodeMap.data.CodeMapCore
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class HelloAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val core = CodeMapCore(project)
        
        core.refreshDatabase(
            onProgress = { /* Можно добавить уведомление в статус бар */ },
            onFinished = {
                Messages.showInfoMessage(project, "Проект успешно спарсен! Данные в codemap_data.json", "CodeMap")
            }
        )
    }
}

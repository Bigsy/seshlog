package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.ContentSearchService
import com.intellij.ide.actions.RevealFileAction
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/** Immediate cache actions are independent of configurable Apply and never run SQL on the EDT. */
internal class SearchCachePanel : JPanel(BorderLayout(0,6)) {
    private val service=ContentSearchService.getInstance()
    private val status=JLabel("Reading lexical index status…")
    private val result=JLabel(" ")
    private val buttons=JPanel(FlowLayout(FlowLayout.LEFT,4,0))
    init {
        add(JPanel(java.awt.GridLayout(0,1)).apply { add(status);add(JLabel(service.cachePath.toString()));add(result) },BorderLayout.NORTH)
        fun button(label:String, action:()->Unit) { buttons.add(JButton(label).apply { addActionListener { action() } }) }
        button("Refresh") { action(ContentSearchService.CacheAction.REFRESH) }
        button("Rebuild…") { if(confirm("Rebuild the lexical index? The next search reindexes local sources."))action(ContentSearchService.CacheAction.REBUILD) }
        button("Clear…") { if(confirm("Clear the saved lexical index? Conversations remain in agent storage."))action(ContentSearchService.CacheAction.CLEAR) }
        button("Retry") { action(ContentSearchService.CacheAction.RETRY) }
        button("Export…") {
            if(confirm("Exports contain sensitive words and their positions. Save only to a trusted location.")) {
                val chooser=JFileChooser().apply { selectedFile=java.io.File("seshlog-lexical.sqlite") }
                if(chooser.showSaveDialog(this)==JFileChooser.APPROVE_OPTION)service.export(chooser.selectedFile.toPath(),::completed)
            }
        }
        button("Import…") {
            val chooser=JFileChooser()
            if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)service.importSnapshot(chooser.selectedFile.toPath(),::completed)
        }
        button("Open cache location") { RevealFileAction.openDirectory(service.cachePath.parent) }
        add(buttons,BorderLayout.CENTER)
        refresh()
    }
    private fun confirm(message:String)=JOptionPane.showConfirmDialog(this,message,"Seshlog search index",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION
    private fun action(action:ContentSearchService.CacheAction) { result.text="Working…";service.maintain(action,::completed) }
    private fun completed(message:String) { result.text=message;refresh() }
    private fun refresh() { service.status { status.text="${it.sources} saved sources · ${it.bytes/1024} KiB · ${it.coverage.indexed}/${it.coverage.eligible} last search sources indexed" } }
}

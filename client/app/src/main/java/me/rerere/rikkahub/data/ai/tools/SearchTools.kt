package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.Settings

/** Compatibility entry point; all callers use the same enhanced response contract. */
fun createSearchTools(settings: Settings): Set<Tool> = createEnhancedSearchTools(settings)

package me.rerere.rikkahub.feature.agent

/** Host-owned policy. Tool descriptions, model output, Skills and MCP cannot create entries. */
internal object TrustedToolRegistry {
    enum class Risk { READ, COMPUTE, VERSION_WRITE, MEMORY_WRITE, PRESENTATION, EXTERNAL }
    enum class Verifier { LOCAL_RECEIPT, ARTIFACT_READBACK, INTERACTION, REMOTE_READBACK, EXACT_MATH, SOURCE_READBACK, MCP_FIXTURE_READBACK, UNSUPPORTED }
    data class Contract(val capability: String, val risk: Risk, val verifier: Verifier)
    private fun contract(capability: String, risk: Risk, verifier: Verifier, vararg names: String) =
        names.associateWith { Contract(capability,risk,verifier) }
    private val tools = buildMap {
        putAll(contract("local.read",Risk.READ,Verifier.LOCAL_RECEIPT,"get_time_info","list_material_files","read_material_file",
            "read_material_text_page","inspect_material_edit","recent_chats","conversation_search","list_material_parse_results",
            "read_material_parse_blocks","get_material_parse_status","index_material_chapters","read_material_chapter","search_material_chapter"))
        putAll(contract("local.compute",Risk.COMPUTE,Verifier.LOCAL_RECEIPT,"eval_javascript"))
        putAll(contract("material.write",Risk.VERSION_WRITE,Verifier.ARTIFACT_READBACK,"create_material_file","edit_material_file",
            "export_material_text_pdf","read_material_word_images","read_material_pdf_pages","render_math_plot",
            "read_material_parse_source_page","read_material_parse_image"))
        putAll(contract("presentation",Risk.PRESENTATION,Verifier.INTERACTION,"render_interactive_ui","revise_interactive_surface","chart_display","display_image"))
        putAll(contract("input.answer",Risk.PRESENTATION,Verifier.INTERACTION,"ask_user"))
        putAll(contract("memory.write",Risk.MEMORY_WRITE,Verifier.LOCAL_RECEIPT,"save_explicit_memory"))
        putAll(contract("web.public",Risk.READ,Verifier.REMOTE_READBACK,"search_web","scrape_web","search_images","extract_web_images"))
        putAll(contract("image.generate",Risk.VERSION_WRITE,Verifier.ARTIFACT_READBACK,"generate_material_image"))
        putAll(contract("document.remote",Risk.EXTERNAL,Verifier.REMOTE_READBACK,"parse_material_enhanced","convert_material_to_pdf","recalculate_material_xlsx","cancel_material_parse"))
        putAll(contract("skill.read",Risk.READ,Verifier.LOCAL_RECEIPT,"use_skill"))
        putAll(contract("mcp.synthetic.read",Risk.EXTERNAL,Verifier.MCP_FIXTURE_READBACK,"read_synthetic_mcp_fixture"))
        putAll(contract("local.compute",Risk.COMPUTE,Verifier.EXACT_MATH,"math_verify"))
        putAll(contract("local.read",Risk.READ,Verifier.SOURCE_READBACK,
            "read_material_source_location","search_material_text","search_material_library","read_math_work","review_math_work"))
    }
    val automaticCapabilities = tools.values.filter { it.risk != Risk.EXTERNAL }.map { it.capability }.toSet()
    fun require(tool: String): Contract = requireNotNull(tools[tool]) { "工具没有可信能力/风险/核验契约：$tool" }
    fun approvalRequired(tool: String) = require(tool).risk == Risk.EXTERNAL
}

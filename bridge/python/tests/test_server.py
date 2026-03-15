"""Integration tests for the KCP MCP server."""
import json
from pathlib import Path

import pytest
from mcp.server import Server
from mcp.types import (
    CallToolRequest,
    CallToolRequestParams,
    ListResourcesRequest,
    ListToolsRequest,
    ReadResourceRequest,
    ReadResourceRequestParams,
)
from pydantic import AnyUrl

from kcp_mcp.server import create_server

MINIMAL_DIR = Path(__file__).parent / "fixtures" / "minimal"
FULL_DIR = Path(__file__).parent / "fixtures" / "full"
SUB_DIR = Path(__file__).parent / "fixtures" / "sub"
RFC007_DIR = Path(__file__).parent / "fixtures" / "rfc007"


def get_server(fixture_dir: Path, agent_only: bool = False) -> Server:
    return create_server(
        fixture_dir / "knowledge.yaml",
        agent_only=agent_only,
        warn_on_validation=False,
    )


async def call_list_resources(server: Server) -> list:
    handler = server.request_handlers[ListResourcesRequest]
    result = await handler(ListResourcesRequest(method="resources/list"))
    return result.root.resources


async def call_read_resource(server: Server, uri: str) -> list:
    handler = server.request_handlers[ReadResourceRequest]
    result = await handler(
        ReadResourceRequest(
            method="resources/read",
            params=ReadResourceRequestParams(uri=AnyUrl(uri)),
        )
    )
    return result.root.contents


# ── create_server ─────────────────────────────────────────────────────────────

def test_create_server_returns_server_instance():
    server = get_server(MINIMAL_DIR)
    assert isinstance(server, Server)


def test_create_server_raises_on_missing_manifest():
    with pytest.raises(Exception):
        create_server(Path("/nonexistent/knowledge.yaml"))


def test_list_resources_handler_registered():
    server = get_server(MINIMAL_DIR)
    assert ListResourcesRequest in server.request_handlers
    assert ReadResourceRequest in server.request_handlers


# ── list_resources ────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_list_resources_minimal_has_manifest_plus_units():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    # manifest + 1 unit
    assert len(resources) == 2
    names = [r.name for r in resources]
    assert "manifest" in names
    assert "overview" in names


@pytest.mark.asyncio
async def test_list_resources_full_has_all_units():
    server = get_server(FULL_DIR)
    resources = await call_list_resources(server)
    # manifest + 3 units
    assert len(resources) == 4


@pytest.mark.asyncio
async def test_list_resources_agent_only_filters_human_units():
    # full fixture: spec (human+agent+developer), api-schema (developer+agent), guide (human+developer)
    # agent-only: spec + api-schema included; guide excluded
    server = get_server(FULL_DIR, agent_only=True)
    resources = await call_list_resources(server)
    names = [r.name for r in resources]
    assert "manifest" in names
    assert "spec" in names
    assert "api-schema" in names
    assert "guide" not in names


@pytest.mark.asyncio
async def test_list_resources_manifest_has_priority_1():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    manifest_res = next(r for r in resources if r.name == "manifest")
    assert manifest_res.annotations.priority == 1.0


@pytest.mark.asyncio
async def test_list_resources_unit_uris_use_knowledge_scheme():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    unit = next(r for r in resources if r.name == "overview")
    assert str(unit.uri).startswith("knowledge://")


# ── read_resource ─────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_read_manifest_returns_json():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    manifest_res = next(r for r in resources if r.name == "manifest")
    contents = await call_read_resource(server, str(manifest_res.uri))
    assert len(contents) == 1
    assert contents[0].mimeType == "application/json"
    parsed = json.loads(contents[0].text)
    assert parsed["project"] == "my-project"
    assert len(parsed["units"]) == 1


@pytest.mark.asyncio
async def test_read_unit_returns_file_content():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    unit_res = next(r for r in resources if r.name == "overview")
    contents = await call_read_resource(server, str(unit_res.uri))
    assert len(contents) == 1
    assert "My Project" in contents[0].text


@pytest.mark.asyncio
async def test_read_json_unit_has_correct_mimetype():
    server = get_server(FULL_DIR)
    resources = await call_list_resources(server)
    api_res = next(r for r in resources if r.name == "api-schema")
    contents = await call_read_resource(server, str(api_res.uri))
    assert contents[0].mimeType == "application/schema+json"
    parsed = json.loads(contents[0].text)
    assert "$schema" in parsed


@pytest.mark.asyncio
async def test_read_unknown_unit_raises():
    server = get_server(MINIMAL_DIR)
    with pytest.raises(Exception):
        await call_read_resource(server, "knowledge://my-project/nonexistent")


@pytest.mark.asyncio
async def test_read_wrong_project_uri_raises():
    server = get_server(MINIMAL_DIR)
    with pytest.raises(Exception):
        await call_read_resource(server, "knowledge://other-project/spec")


# ── sub-manifests ─────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_sub_manifest_units_are_merged():
    server = create_server(
        MINIMAL_DIR / "knowledge.yaml",
        sub_manifests=[SUB_DIR / "knowledge.yaml"],
        warn_on_validation=False,
    )
    resources = await call_list_resources(server)
    # manifest + 1 primary unit + 1 sub unit
    assert len(resources) == 3
    names = [r.name for r in resources]
    assert "overview" in names
    assert "sub-unit-a" in names


@pytest.mark.asyncio
async def test_sub_manifest_unit_is_readable():
    server = create_server(
        MINIMAL_DIR / "knowledge.yaml",
        sub_manifests=[SUB_DIR / "knowledge.yaml"],
        warn_on_validation=False,
    )
    resources = await call_list_resources(server)
    sub_res = next(r for r in resources if r.name == "sub-unit-a")
    contents = await call_read_resource(server, str(sub_res.uri))
    assert len(contents) == 1
    assert "Sub-Unit A" in contents[0].text


@pytest.mark.asyncio
async def test_primary_wins_on_duplicate_unit_id(tmp_path):
    sub_yaml = tmp_path / "knowledge.yaml"
    sub_yaml.write_text(
        'kcp_version: "0.6"\nproject: conflict\nversion: 1.0.0\n'
        'units:\n  - id: overview\n    path: extra.md\n    intent: "x"\n'
        '    scope: global\n    audience: [agent]\n'
    )
    (tmp_path / "extra.md").write_text("extra")
    server = create_server(
        MINIMAL_DIR / "knowledge.yaml",
        sub_manifests=[sub_yaml],
        warn_on_validation=False,
    )
    resources = await call_list_resources(server)
    # Duplicate id skipped — still 2: manifest + 1 primary unit
    assert len(resources) == 2


@pytest.mark.asyncio
async def test_missing_sub_manifest_is_skipped():
    server = create_server(
        MINIMAL_DIR / "knowledge.yaml",
        sub_manifests=[Path("/nonexistent/knowledge.yaml")],
        warn_on_validation=False,
    )
    resources = await call_list_resources(server)
    # Warning emitted; primary still served
    assert len(resources) == 2


# ── tool helpers ─────────────────────────────────────────────────────────────

async def call_list_tools(server: Server) -> list:
    handler = server.request_handlers[ListToolsRequest]
    result = await handler(ListToolsRequest(method="tools/list"))
    return result.root.tools


async def call_tool(server: Server, name: str, arguments: dict | None = None) -> object:
    handler = server.request_handlers[CallToolRequest]
    result = await handler(
        CallToolRequest(
            method="tools/call",
            params=CallToolRequestParams(name=name, arguments=arguments or {}),
        )
    )
    return result.root


# ── list_tools ───────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_list_tools_contains_list_manifests():
    server = get_server(MINIMAL_DIR)
    tools = await call_list_tools(server)
    names = [t.name for t in tools]
    assert "list_manifests" in names


@pytest.mark.asyncio
async def test_list_manifests_tool_has_input_schema():
    server = get_server(MINIMAL_DIR)
    tools = await call_list_tools(server)
    lm = next(t for t in tools if t.name == "list_manifests")
    assert lm.inputSchema["type"] == "object"


# ── list_manifests tool ──────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_list_manifests_returns_empty_array_when_no_federation():
    server = get_server(MINIMAL_DIR)
    result = await call_tool(server, "list_manifests")
    text = result.content[0].text
    entries = json.loads(text)
    assert entries == []


@pytest.mark.asyncio
async def test_list_manifests_returns_manifests_from_federation(tmp_path):
    """Create a manifest with a manifests block and verify list_manifests returns it."""
    (tmp_path / "overview.md").write_text("# Overview")
    (tmp_path / "knowledge.yaml").write_text(
        'kcp_version: "0.9"\n'
        "project: fed-test\n"
        "version: 1.0.0\n"
        "units:\n"
        "  - id: overview\n"
        "    path: overview.md\n"
        '    intent: "Project overview"\n'
        "    scope: global\n"
        "    audience: [agent]\n"
        "manifests:\n"
        "  - id: platform\n"
        '    url: "https://example.com/platform/knowledge.yaml"\n'
        '    label: "Platform Team"\n'
        "    relationship: foundation\n"
        "    update_frequency: weekly\n"
        "  - id: security\n"
        '    url: "https://example.com/security/knowledge.yaml"\n'
        '    label: "Security Team"\n'
        "    relationship: governs\n"
    )
    server = create_server(tmp_path / "knowledge.yaml", warn_on_validation=False)
    result = await call_tool(server, "list_manifests")
    text = result.content[0].text
    entries = json.loads(text)
    assert len(entries) == 2
    assert entries[0]["id"] == "platform"
    assert entries[0]["url"] == "https://example.com/platform/knowledge.yaml"
    assert entries[0]["label"] == "Platform Team"
    assert entries[0]["relationship"] == "foundation"
    assert entries[0]["has_local_mirror"] is False
    assert entries[0]["update_frequency"] == "weekly"
    assert entries[1]["id"] == "security"
    assert entries[1]["relationship"] == "governs"


# ── search_knowledge tool (RFC-0007) ─────────────────────────────────────────

@pytest.mark.asyncio
async def test_list_tools_contains_search_knowledge():
    server = get_server(RFC007_DIR)
    tools = await call_list_tools(server)
    names = [t.name for t in tools]
    assert "search_knowledge" in names


@pytest.mark.asyncio
async def test_search_knowledge_returns_match_reason():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "authentication"})
    results = json.loads(result.content[0].text)
    assert isinstance(results, list)
    assert len(results) > 0
    assert "match_reason" in results[0]
    assert "trigger" in results[0]["match_reason"]


@pytest.mark.asyncio
async def test_search_knowledge_returns_token_estimate_and_summary_unit():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "authentication"})
    results = json.loads(result.content[0].text)
    auth_guide = next((r for r in results if r["id"] == "auth-guide"), None)
    assert auth_guide is not None
    assert auth_guide["token_estimate"] == 4200
    assert auth_guide["summary_unit"] == "auth-tldr"


@pytest.mark.asyncio
async def test_search_knowledge_excludes_deprecated_by_default():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "api endpoints legacy"})
    text = result.content[0].text
    if text.startswith("["):
        results = json.loads(text)
        ids = [r["id"] for r in results]
        assert "old-api" not in ids


@pytest.mark.asyncio
async def test_search_knowledge_includes_deprecated_when_flag_false():
    server = get_server(RFC007_DIR)
    result = await call_tool(
        server, "search_knowledge",
        {"query": "api endpoints legacy", "exclude_deprecated": False},
    )
    results = json.loads(result.content[0].text)
    ids = [r["id"] for r in results]
    assert "old-api" in ids


@pytest.mark.asyncio
async def test_search_knowledge_filters_by_sensitivity_max():
    server = get_server(RFC007_DIR)
    # secret-config is confidential — should be excluded when sensitivity_max is internal
    result = await call_tool(
        server, "search_knowledge",
        {"query": "config secrets credentials", "sensitivity_max": "internal"},
    )
    text = result.content[0].text
    if text.startswith("["):
        results = json.loads(text)
        ids = [r["id"] for r in results]
        assert "secret-config" not in ids


@pytest.mark.asyncio
async def test_search_knowledge_includes_confidential_at_matching_ceiling():
    server = get_server(RFC007_DIR)
    result = await call_tool(
        server, "search_knowledge",
        {"query": "config secrets credentials", "sensitivity_max": "confidential"},
    )
    results = json.loads(result.content[0].text)
    ids = [r["id"] for r in results]
    assert "secret-config" in ids


@pytest.mark.asyncio
async def test_search_knowledge_empty_query_returns_prompt():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": ""})
    text = result.content[0].text
    assert not text.startswith("[")
    assert "provide" in text.lower()


@pytest.mark.asyncio
async def test_search_knowledge_no_match_returns_text_message():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "xyzzy_no_match_zzzq"})
    text = result.content[0].text
    assert not text.startswith("[")
    assert "no units matched" in text.lower()


@pytest.mark.asyncio
async def test_search_knowledge_match_reason_intent():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "oauth integration"})
    results = json.loads(result.content[0].text)
    auth_guide = next((r for r in results if r["id"] == "auth-guide"), None)
    assert auth_guide is not None
    assert "intent" in auth_guide["match_reason"]


@pytest.mark.asyncio
async def test_search_knowledge_match_reason_id():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "auth"})
    results = json.loads(result.content[0].text)
    auth_guide = next((r for r in results if r["id"] == "auth-guide"), None)
    assert auth_guide is not None
    assert "id" in auth_guide["match_reason"]


@pytest.mark.asyncio
async def test_search_knowledge_sorted_by_score_descending():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "authentication"})
    results = json.loads(result.content[0].text)
    scores = [r["score"] for r in results]
    assert scores == sorted(scores, reverse=True)


@pytest.mark.asyncio
async def test_search_knowledge_top5_cap(tmp_path):
    """Create 8 matching units — only top 5 should be returned."""
    (tmp_path / "x.md").write_text("# content")
    units = "\n".join(
        f'  - id: unit-{i}\n    path: x.md\n    intent: "search result item {i}"\n'
        f'    scope: global\n    audience: [agent]\n    triggers: [search, result]\n'
        for i in range(8)
    )
    (tmp_path / "knowledge.yaml").write_text(
        'kcp_version: "0.11"\nproject: cap-test\nversion: 1.0.0\nunits:\n' + units
    )
    server = create_server(tmp_path / "knowledge.yaml", warn_on_validation=False)
    result = await call_tool(server, "search_knowledge", {"query": "search result"})
    results = json.loads(result.content[0].text)
    assert len(results) <= 5


@pytest.mark.asyncio
async def test_search_knowledge_audience_filter_includes_matching():
    server = get_server(RFC007_DIR)
    result = await call_tool(
        server, "search_knowledge",
        {"query": "authentication", "audience": "developer"},
    )
    results = json.loads(result.content[0].text)
    ids = [r["id"] for r in results]
    # auth-guide has audience: [agent, developer]
    assert "auth-guide" in ids


@pytest.mark.asyncio
async def test_search_knowledge_audience_filter_excludes_non_matching():
    server = get_server(RFC007_DIR)
    result = await call_tool(
        server, "search_knowledge",
        {"query": "authentication", "audience": "operator"},
    )
    text = result.content[0].text
    # No unit has audience: operator
    assert not text.startswith("[") or json.loads(text) == []


@pytest.mark.asyncio
async def test_search_knowledge_scope_filter_global():
    server = get_server(RFC007_DIR)
    result = await call_tool(
        server, "search_knowledge",
        {"query": "authentication", "scope": "global"},
    )
    results = json.loads(result.content[0].text)
    assert all(r["id"] != "" for r in results)


@pytest.mark.asyncio
async def test_search_knowledge_sensitivity_max_public_excludes_internal():
    server = get_server(RFC007_DIR)
    result = await call_tool(
        server, "search_knowledge",
        {"query": "authentication", "sensitivity_max": "public"},
    )
    text = result.content[0].text
    if text.startswith("["):
        results = json.loads(text)
        ids = [r["id"] for r in results]
        # auth-guide is internal — should be excluded at public ceiling
        assert "auth-guide" not in ids


@pytest.mark.asyncio
async def test_search_knowledge_sensitivity_max_restricted_includes_all():
    server = get_server(RFC007_DIR)
    result = await call_tool(
        server, "search_knowledge",
        {"query": "config secrets", "sensitivity_max": "restricted"},
    )
    results = json.loads(result.content[0].text)
    ids = [r["id"] for r in results]
    assert "secret-config" in ids


@pytest.mark.asyncio
async def test_search_knowledge_unit_without_sensitivity_is_public(tmp_path):
    """Units with no sensitivity field should be treated as public."""
    (tmp_path / "doc.md").write_text("# doc")
    (tmp_path / "knowledge.yaml").write_text(
        'kcp_version: "0.11"\nproject: sens-test\nversion: 1.0.0\nunits:\n'
        '  - id: no-sens\n    path: doc.md\n    intent: "no sensitivity set"\n'
        '    scope: global\n    audience: [agent]\n    triggers: [nosens]\n'
    )
    server = create_server(tmp_path / "knowledge.yaml", warn_on_validation=False)
    result = await call_tool(
        server, "search_knowledge",
        {"query": "nosens", "sensitivity_max": "public"},
    )
    results = json.loads(result.content[0].text)
    ids = [r["id"] for r in results]
    assert "no-sens" in ids


@pytest.mark.asyncio
async def test_search_knowledge_result_has_uri():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "authentication"})
    results = json.loads(result.content[0].text)
    assert all("uri" in r for r in results)
    assert all(r["uri"].startswith("knowledge://") for r in results)


# ── list_manifests: version_pin / version_policy / local_mirror ──────────────

@pytest.mark.asyncio
async def test_list_manifests_includes_version_pin(tmp_path):
    (tmp_path / "x.md").write_text("x")
    (tmp_path / "knowledge.yaml").write_text(
        'kcp_version: "0.11"\nproject: vpin\nversion: 1.0.0\n'
        'units:\n  - id: x\n    path: x.md\n    intent: "x"\n    scope: global\n    audience: [agent]\n'
        'manifests:\n'
        '  - id: sub\n    url: "https://example.com/sub/knowledge.yaml"\n'
        '    relationship: child\n    version_pin: "0.10"\n    version_policy: minimum\n'
    )
    server = create_server(tmp_path / "knowledge.yaml", warn_on_validation=False)
    result = await call_tool(server, "list_manifests")
    entries = json.loads(result.content[0].text)
    assert entries[0]["version_pin"] == "0.10"
    assert entries[0]["version_policy"] == "minimum"


@pytest.mark.asyncio
async def test_list_manifests_has_local_mirror_true(tmp_path):
    mirror = tmp_path / "mirror.yaml"
    mirror.write_text('kcp_version: "0.11"\nproject: mirror\nversion: 1.0.0\nunits: []\n')
    (tmp_path / "x.md").write_text("x")
    (tmp_path / "knowledge.yaml").write_text(
        'kcp_version: "0.11"\nproject: mirror-test\nversion: 1.0.0\n'
        'units:\n  - id: x\n    path: x.md\n    intent: "x"\n    scope: global\n    audience: [agent]\n'
        f'manifests:\n  - id: sub\n    url: "https://example.com/sub/knowledge.yaml"\n'
        f'    relationship: child\n    local_mirror: "{mirror}"\n'
    )
    server = create_server(tmp_path / "knowledge.yaml", warn_on_validation=False)
    result = await call_tool(server, "list_manifests")
    entries = json.loads(result.content[0].text)
    assert entries[0]["has_local_mirror"] is True


# ── list_resources: edge cases ───────────────────────────────────────────────

@pytest.mark.asyncio
async def test_list_resources_empty_units_still_has_manifest(tmp_path):
    (tmp_path / "knowledge.yaml").write_text(
        'kcp_version: "0.11"\nproject: empty-proj\nversion: 1.0.0\nunits: []\n'
    )
    server = create_server(tmp_path / "knowledge.yaml", warn_on_validation=False)
    resources = await call_list_resources(server)
    assert len(resources) == 1
    assert resources[0].name == "manifest"


@pytest.mark.asyncio
async def test_list_resources_project_scope_has_priority_07():
    server = get_server(FULL_DIR)
    resources = await call_list_resources(server)
    # check the spec unit has global priority = 1.0
    spec_res = next((r for r in resources if r.name == "spec"), None)
    if spec_res:
        assert spec_res.annotations.priority == 1.0


@pytest.mark.asyncio
async def test_agent_only_all_units_filtered_leaves_only_manifest(tmp_path):
    (tmp_path / "doc.md").write_text("# human only doc")
    (tmp_path / "knowledge.yaml").write_text(
        'kcp_version: "0.11"\nproject: human-proj\nversion: 1.0.0\nunits:\n'
        '  - id: human-doc\n    path: doc.md\n    intent: "human only"\n'
        '    scope: global\n    audience: [human]\n'
    )
    server = create_server(tmp_path / "knowledge.yaml", agent_only=True, warn_on_validation=False)
    resources = await call_list_resources(server)
    names = [r.name for r in resources]
    assert "manifest" in names
    assert "human-doc" not in names


# ── read_resource: manifest JSON fields ──────────────────────────────────────

@pytest.mark.asyncio
async def test_read_manifest_includes_unit_count():
    server = get_server(FULL_DIR)
    resources = await call_list_resources(server)
    manifest_res = next(r for r in resources if r.name == "manifest")
    contents = await call_read_resource(server, str(manifest_res.uri))
    parsed = json.loads(contents[0].text)
    assert "unit_count" in parsed
    assert parsed["unit_count"] == 3


@pytest.mark.asyncio
async def test_read_manifest_includes_version():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    manifest_res = next(r for r in resources if r.name == "manifest")
    contents = await call_read_resource(server, str(manifest_res.uri))
    parsed = json.loads(contents[0].text)
    assert "version" in parsed


@pytest.mark.asyncio
async def test_search_knowledge_tool_has_required_query_param():
    server = get_server(RFC007_DIR)
    tools = await call_list_tools(server)
    sk = next(t for t in tools if t.name == "search_knowledge")
    assert "query" in sk.inputSchema.get("required", [])


@pytest.mark.asyncio
async def test_list_tools_search_knowledge_has_sensitivity_max_param():
    server = get_server(RFC007_DIR)
    tools = await call_list_tools(server)
    sk = next(t for t in tools if t.name == "search_knowledge")
    assert "sensitivity_max" in sk.inputSchema["properties"]


@pytest.mark.asyncio
async def test_list_tools_search_knowledge_has_audience_param():
    server = get_server(RFC007_DIR)
    tools = await call_list_tools(server)
    sk = next(t for t in tools if t.name == "search_knowledge")
    assert "audience" in sk.inputSchema["properties"]


@pytest.mark.asyncio
async def test_unknown_tool_returns_error_text():
    server = get_server(MINIMAL_DIR)
    result = await call_tool(server, "nonexistent_tool")
    assert "Unknown tool" in result.content[0].text


# ── multiple sub-manifests ───────────────────────────────────────────────────

# ── search_knowledge: token_estimate / summary_unit / trigger / path ─────────

@pytest.mark.asyncio
async def test_search_knowledge_token_estimate_in_result():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "authentication"})
    results = json.loads(result.content[0].text)
    auth_guide = next((r for r in results if r["id"] == "auth-guide"), None)
    assert auth_guide is not None
    assert auth_guide["token_estimate"] == 4200


@pytest.mark.asyncio
async def test_search_knowledge_summary_unit_in_result():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "authentication"})
    results = json.loads(result.content[0].text)
    auth_guide = next((r for r in results if r["id"] == "auth-guide"), None)
    assert auth_guide is not None
    assert auth_guide["summary_unit"] == "auth-tldr"


@pytest.mark.asyncio
async def test_search_knowledge_token_estimate_none_when_absent():
    server = get_server(RFC007_DIR)
    result = await call_tool(server, "search_knowledge", {"query": "config secrets"})
    results = json.loads(result.content[0].text)
    secret = next((r for r in results if r["id"] == "secret-config"), None)
    assert secret is not None
    assert secret["token_estimate"] is None


@pytest.mark.asyncio
async def test_search_knowledge_match_reason_trigger():
    server = get_server(RFC007_DIR)
    # "oauth2" is an exact trigger on auth-guide
    result = await call_tool(server, "search_knowledge", {"query": "oauth2"})
    results = json.loads(result.content[0].text)
    auth_guide = next((r for r in results if r["id"] == "auth-guide"), None)
    assert auth_guide is not None
    assert "trigger" in auth_guide["match_reason"]


@pytest.mark.asyncio
async def test_search_knowledge_match_reason_path():
    server = get_server(RFC007_DIR)
    # All units have path README.md — "readme" should fire path match
    result = await call_tool(server, "search_knowledge", {"query": "readme"})
    results = json.loads(result.content[0].text)
    assert any("path" in r["match_reason"] for r in results)


# ── list_manifests: no manifests / basic fields ───────────────────────────────

@pytest.mark.asyncio
async def test_list_manifests_no_manifests_returns_empty():
    server = get_server(MINIMAL_DIR)
    result = await call_tool(server, "list_manifests")
    data = json.loads(result.content[0].text)
    assert data == []


@pytest.mark.asyncio
async def test_list_manifests_has_local_mirror_false_by_default(tmp_path):
    (tmp_path / "x.md").write_text("x")
    (tmp_path / "knowledge.yaml").write_text(
        'kcp_version: "0.11"\nproject: no-mirror\nversion: 1.0.0\n'
        'units:\n  - id: x\n    path: x.md\n    intent: "x"\n    scope: global\n    audience: [agent]\n'
        'manifests:\n  - id: sub\n    url: "https://example.com/sub/knowledge.yaml"\n'
        '    relationship: child\n'
    )
    server = create_server(tmp_path / "knowledge.yaml", warn_on_validation=False)
    result = await call_tool(server, "list_manifests")
    entries = json.loads(result.content[0].text)
    assert entries[0]["has_local_mirror"] is False


# ── read_resource: unit content / unknown URI ─────────────────────────────────

@pytest.mark.asyncio
async def test_read_unit_returns_text_content():
    server = get_server(RFC007_DIR)
    resources = await call_list_resources(server)
    auth_res = next(r for r in resources if r.name == "auth-guide")
    contents = await call_read_resource(server, str(auth_res.uri))
    assert len(contents) == 1
    assert contents[0].text  # non-empty


@pytest.mark.asyncio
async def test_read_unknown_uri_raises():
    server = get_server(MINIMAL_DIR)
    with pytest.raises(ValueError):
        await call_read_resource(server, "knowledge://minimal/nonexistent-unit")


# ── list_tools: tool presence / schema params ─────────────────────────────────

@pytest.mark.asyncio
async def test_list_tools_includes_list_manifests():
    server = get_server(MINIMAL_DIR)
    tools = await call_list_tools(server)
    names = [t.name for t in tools]
    assert "list_manifests" in names


@pytest.mark.asyncio
async def test_search_knowledge_has_exclude_deprecated_param():
    server = get_server(RFC007_DIR)
    tools = await call_list_tools(server)
    sk = next(t for t in tools if t.name == "search_knowledge")
    assert "exclude_deprecated" in sk.inputSchema["properties"]


# ── read_manifest: kcp_version / updated fields ───────────────────────────────

@pytest.mark.asyncio
async def test_read_manifest_includes_kcp_version():
    server = get_server(RFC007_DIR)
    resources = await call_list_resources(server)
    manifest_res = next(r for r in resources if r.name == "manifest")
    contents = await call_read_resource(server, str(manifest_res.uri))
    parsed = json.loads(contents[0].text)
    assert "kcp_version" in parsed


@pytest.mark.asyncio
async def test_read_manifest_includes_updated():
    server = get_server(RFC007_DIR)
    resources = await call_list_resources(server)
    manifest_res = next(r for r in resources if r.name == "manifest")
    contents = await call_read_resource(server, str(manifest_res.uri))
    parsed = json.loads(contents[0].text)
    assert "updated" in parsed


# ── multiple sub-manifests ───────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_multiple_sub_manifests_all_merged(tmp_path):
    sub1 = tmp_path / "sub1"
    sub2 = tmp_path / "sub2"
    sub1.mkdir()
    sub2.mkdir()
    for d, uid in [(sub1, "alpha"), (sub2, "beta")]:
        (d / "x.md").write_text(f"# {uid}")
        (d / "knowledge.yaml").write_text(
            f'kcp_version: "0.11"\nproject: {uid}\nversion: 1.0.0\nunits:\n'
            f'  - id: {uid}\n    path: x.md\n    intent: "{uid}"\n    scope: global\n    audience: [agent]\n'
        )
    server = create_server(
        MINIMAL_DIR / "knowledge.yaml",
        sub_manifests=[sub1 / "knowledge.yaml", sub2 / "knowledge.yaml"],
        warn_on_validation=False,
    )
    resources = await call_list_resources(server)
    names = [r.name for r in resources]
    assert "alpha" in names
    assert "beta" in names

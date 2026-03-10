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

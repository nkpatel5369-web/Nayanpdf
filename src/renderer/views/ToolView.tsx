import React from "react";
import type { ToolId } from "../store";
import { useApp } from "../store";
import { toolById, TOOLS } from "../tools";
import { CompressTool, ExtractImagesTool, MergeTool, MetadataTool, OcrTool, ProtectTool, SplitTool, WatermarkTool } from "./tools/BasicTools";
import { ConvertTool, CreateTool } from "./tools/ConvertTools";
import { CompareTool, DocModeTool, UtilitiesTool } from "./tools/MoreTools";
import { SearchTool } from "./tools/SearchTool";
import { ScanTool } from "./tools/ScanTool";

export function ToolView({ tool, args }: { tool: ToolId; args?: any }) {
  const setView = useApp((s) => s.setView);
  const t = toolById(tool);
  const body = (() => {
    switch (tool) {
      case "merge": return <MergeTool />;
      case "split": return <SplitTool args={args} />;
      case "compress": return <CompressTool args={args} />;
      case "convert": return <ConvertTool args={args} />;
      case "ocr": return <OcrTool args={args} />;
      case "create": return <CreateTool args={args} />;
      case "protect": return <ProtectTool args={args} />;
      case "compare": return <CompareTool />;
      case "extractImages": return <ExtractImagesTool args={args} />;
      case "watermark": return <WatermarkTool args={args} />;
      case "metadata": return <MetadataTool args={args} />;
      case "utilities": return <UtilitiesTool args={args} />;
      case "search": return <SearchTool />;
      case "scan": return <ScanTool />;
      default: return t ? <DocModeTool tool={t} /> : null;
    }
  })();
  return (
    <div style={{ display: "grid", gridTemplateColumns: "210px 1fr", height: "100%" }}>
      <div className="side" style={{ width: "auto", overflow: "auto" }}>
        <div className="panel-pad subtle" style={{ fontWeight: 600, paddingBottom: 4 }}>TOOLS</div>
        {TOOLS.map((x) => (
          <div key={x.id} className={"list-item row" + (x.id === tool ? " on" : "")} style={{ gap: 9, padding: "8px 14px", border: "none" }}
            onClick={() => setView({ kind: "tool", tool: x.id })}>
            <x.icon size={16} color={x.color} />
            <span>{x.title}</span>
          </div>
        ))}
      </div>
      <div style={{ minWidth: 0, minHeight: 0 }}>{body}</div>
    </div>
  );
}

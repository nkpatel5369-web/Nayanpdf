import {
  ArrowLeftRight, Combine, FileCog, FileDown, FileImage, FileInput, FileLock2, FilePen, FilePlus2, FileSearch, FileSignature, FileType2,
  FormInput, Highlighter, Images, LayoutGrid, ScanText, Scissors, Shredder, Stamp, Tags, Wrench, TextSearch, ClipboardList,
} from "lucide-react";
import type { ToolId } from "./store";

export interface ToolDef {
  id: ToolId;
  title: string;
  desc: string;
  icon: any;
  color: string;
  /** Tools that work inside an open document. */
  docMode?: "edit" | "comment" | "pages" | "forms" | "prepare" | "redact" | "sign";
}

export const TOOLS: ToolDef[] = [
  { id: "edit", title: "Edit PDF", desc: "Change existing text, images and add content", icon: FilePen, color: "#3f51d9", docMode: "edit" },
  { id: "merge", title: "Merge PDFs", desc: "Combine files in any order", icon: Combine, color: "#7048e8" },
  { id: "split", title: "Split PDF", desc: "By page count, ranges or bookmarks", icon: Scissors, color: "#d6336c" },
  { id: "compress", title: "Compress PDF", desc: "Reduce size, keep text sharp", icon: FileDown, color: "#2f9e44" },
  { id: "convert", title: "Convert PDF", desc: "Word, Excel, PowerPoint, images, text", icon: ArrowLeftRight, color: "#1c7ed6" },
  { id: "organize", title: "Organize Pages", desc: "Reorder, rotate, insert, delete", icon: LayoutGrid, color: "#f08c00", docMode: "pages" },
  { id: "ocr", title: "OCR PDF", desc: "Make scanned documents searchable", icon: ScanText, color: "#0c8599" },
  { id: "create", title: "Create PDF", desc: "From Office files, images, text, HTML", icon: FilePlus2, color: "#5c940d" },
  { id: "annotate", title: "Annotate PDF", desc: "Highlight, comment, draw, stamp", icon: Highlighter, color: "#e8590c", docMode: "comment" },
  { id: "sign", title: "Sign PDF", desc: "Signature images and digital signatures", icon: FileSignature, color: "#364fc7", docMode: "sign" },
  { id: "protect", title: "Protect PDF", desc: "Passwords, permissions, unlock", icon: FileLock2, color: "#495057" },
  { id: "redact", title: "Redact PDF", desc: "Permanently remove sensitive content", icon: Shredder, color: "#c92a2a", docMode: "redact" },
  { id: "compare", title: "Compare PDFs", desc: "Find text and visual differences", icon: FileSearch, color: "#9c36b5" },
  { id: "search", title: "Search Across Files", desc: "Find words in a folder of PDFs", icon: TextSearch, color: "#0b7285" },
  { id: "extractImages", title: "Extract Images", desc: "Save embedded pictures", icon: Images, color: "#087f5b" },
  { id: "forms", title: "Fill PDF Forms", desc: "Fill, save and flatten forms", icon: FormInput, color: "#1971c2", docMode: "forms" },
  { id: "prepareForm", title: "Prepare a Form", desc: "Add fields or detect blanks to make a fillable form", icon: ClipboardList, color: "#1864ab", docMode: "prepare" },
  { id: "watermark", title: "Watermark & Numbers", desc: "Watermarks, headers, page numbers, Bates", icon: Stamp, color: "#a61e4d" },
  { id: "metadata", title: "Properties", desc: "View, edit or remove metadata", icon: Tags, color: "#5f3dc4" },
  { id: "utilities", title: "More Utilities", desc: "PDF/A, repair, attachments, page size...", icon: Wrench, color: "#495057" },
];

export const CONVERT_ICONS = { FileType2, FileImage, FileInput, FileCog };

export function toolById(id: ToolId): ToolDef | undefined {
  return TOOLS.find((t) => t.id === id);
}

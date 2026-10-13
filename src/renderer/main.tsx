import React from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";
import "pdfjs-dist/web/pdf_viewer.css";
import { App } from "./App";

createRoot(document.getElementById("root")!).render(<App />);

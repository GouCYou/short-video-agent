"""Private document extraction; uploads are data, independent of vector indexing."""
import os
import posixpath
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET
from pydantic import Field
from .errors import ServiceError
from .models import Schema
from .knowledge import extract_file


class ExtractRequest(Schema):
    path: str = Field(min_length=1, max_length=2000)


def xml_archive(path, kind):
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > 10000 or sum(info.file_size for info in archive.infolist()) > 80 * 1024 * 1024:
            raise ServiceError("文档解压后超过限制。", 413, "FILE_TOO_LARGE")
        if kind == "pptx":
            names = sorted((name for name in archive.namelist() if re.fullmatch(r"ppt/slides/slide\d+\.xml", name)),
                           key=lambda name: int(re.search(r"slide(\d+)", name).group(1)))
            if "ppt/presentation.xml" in archive.namelist() and "ppt/_rels/presentation.xml.rels" in archive.namelist():
                relationships = {node.attrib.get("Id"): posixpath.normpath("ppt/" + node.attrib.get("Target", ""))
                                 for node in ET.fromstring(archive.read("ppt/_rels/presentation.xml.rels"))
                                 if node.attrib.get("Type", "").endswith("/slide") and node.attrib.get("TargetMode") != "External"}
                order = [relationships.get(node.attrib.get("{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"))
                         for node in ET.fromstring(archive.read("ppt/presentation.xml")).iter() if node.tag.endswith("}sldId")]
                if order and all(name in names for name in order):
                    names = order
            if len(names) > 500:
                raise ServiceError("演示文稿最多支持 500 页。", 413, "FILE_TOO_LARGE")
            parts, readable = [], False
            for i, name in enumerate(names):
                root = ET.fromstring(archive.read(name))
                texts = [element.text for element in root.iter() if element.tag.endswith("}t") and element.text]
                rels = posixpath.dirname(name) + "/_rels/" + posixpath.basename(name) + ".rels"
                if rels in archive.namelist():
                    for rel in ET.fromstring(archive.read(rels)):
                        if rel.attrib.get("Type", "").endswith("/notesSlide") and rel.attrib.get("TargetMode") != "External":
                            notes = posixpath.normpath(posixpath.dirname(name) + "/" + rel.attrib.get("Target", ""))
                            if notes.startswith("ppt/") and notes in archive.namelist():
                                note = ET.fromstring(archive.read(notes))
                                texts += [element.text for element in note.iter() if element.tag.endswith("}t") and element.text]
                readable = readable or bool(texts)
                parts.append(f"第 {i+1} 页\n" + "\n".join(texts))
            return "\n\n".join(parts) if readable else ""
        root = ET.fromstring(archive.read("content.xml"))
        return "\n".join("".join(node.itertext()) for node in root.iter() if node.tag.endswith(("}p", "}h")))


def extract_document(raw_path: str, root: Path):
    path = Path(raw_path).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file() or path.is_symlink():
        raise ServiceError("上传文件路径无效。", 400, "INVALID_FILE")
    if path.stat().st_size > 20 * 1024 * 1024:
        raise ServiceError("文件超过 20 MB。", 413, "FILE_TOO_LARGE")
    ext = path.suffix.lower()
    try:
        if ext in {".docx", ".pdf", ".txt", ".md"}:
            text = extract_file(str(path), root)
        elif ext == ".csv":
            try:
                text = path.read_text(encoding="utf-8-sig")
            except UnicodeDecodeError:
                text = path.read_text(encoding="gb18030")
        elif ext in {".pptx", ".odt", ".odp"}:
            text = xml_archive(path, "pptx" if ext == ".pptx" else "open-document")
        elif ext in {".doc", ".ppt", ".rtf"}:
            converter = os.getenv("DOCUMENT_CONVERTER") or shutil.which("soffice") or shutil.which("libreoffice")
            if not converter:
                raise ServiceError("读取 DOC、PPT、RTF 需要 LibreOffice；也可另存为 DOCX、PPTX 后上传。", 503, "CONVERTER_UNAVAILABLE")
            with tempfile.TemporaryDirectory(prefix="agent-document-") as folder:
                out = Path(folder)
                fmt = "pptx" if ext == ".ppt" else "docx"
                subprocess.run([converter, "-env:UserInstallation=" + (out / "profile").as_uri(), "--headless", "--convert-to", fmt,
                                "--outdir", str(out), str(path)], timeout=45, check=True, capture_output=True)
                converted = out / (path.stem + "." + fmt)
                if not converted.is_file():
                    raise ValueError("conversion produced no file")
                text = xml_archive(converted, "pptx") if fmt == "pptx" else extract_file(str(converted), out)
        else:
            raise ServiceError("该文件格式暂不支持。", 400, "INVALID_FILE")
        text = text.replace("\x00", "").strip()
        if not text:
            raise ServiceError("没有读取到文字；扫描版 PDF 请先进行 OCR。", 400, "EMPTY_DOCUMENT")
        if len(text) > 200000:
            raise ServiceError("文件文字超过 20 万字，请拆分后上传。", 413, "FILE_TOO_LARGE")
        return {"text": text, "characters": len(text)}
    except ServiceError:
        raise
    except subprocess.TimeoutExpired as exc:
        raise ServiceError("文档转换超时，请另存为 DOCX 或 PPTX 后上传。", 400, "PARSE_TIMEOUT") from exc
    except Exception as exc:
        raise ServiceError("无法解析文档，请检查文件是否损坏、加密或格式不符。", 400, "PARSE_ERROR") from exc

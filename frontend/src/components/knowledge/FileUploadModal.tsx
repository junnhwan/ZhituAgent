import { useState, useCallback, useRef } from "react";
import { motion, AnimatePresence } from "framer-motion";
import {
  X, Upload, FileText, CheckCircle, AlertCircle,
  Loader2, CloudUpload,
} from "lucide-react";
import { uploadFile, pollUploadStatus } from "../../api/files";
import "./FileUploadModal.css";

type UploadState = "idle" | "uploading" | "parsing" | "done" | "error";

interface FileUploadModalProps {
  open: boolean;
  onClose: () => void;
}

export default function FileUploadModal({ open, onClose }: FileUploadModalProps) {
  const [file, setFile] = useState<File | null>(null);
  const [state, setState] = useState<UploadState>("idle");
  const [progress, setProgress] = useState(0);
  const [parseStatus, setParseStatus] = useState<string | null>(null);
  const [, setUploadId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const reset = useCallback(() => {
    setFile(null);
    setState("idle");
    setProgress(0);
    setParseStatus(null);
    setUploadId(null);
    setError(null);
  }, []);

  const handleClose = useCallback(() => {
    reset();
    onClose();
  }, [reset, onClose]);

  const handleFile = useCallback((f: File) => {
    setFile(f);
    setError(null);
  }, []);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragActive(false);
    const f = e.dataTransfer.files[0];
    if (f) handleFile(f);
  }, [handleFile]);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragActive(true);
  }, []);

  const handleDragLeave = useCallback(() => {
    setDragActive(false);
  }, []);

  const handleUpload = useCallback(async () => {
    if (!file) return;

    setState("uploading");
    setProgress(0);
    setError(null);

    try {
      const resp = await uploadFile(file, setProgress);
      setUploadId(resp.uploadId);

      if (resp.chunksIngested >= 0) {
        // Sync mode — already done
        setState("done");
        setParseStatus("INDEXED");
      } else {
        // Async mode — poll for status
        setState("parsing");
        setParseStatus("QUEUED");

        const finalStatus = await pollUploadStatus(resp.uploadId, (s) => {
          setParseStatus(s.parseStatus);
        });

        if (finalStatus.parseStatus === "FAILED") {
          setState("error");
          setError("文件解析失败");
        } else {
          setState("done");
        }
      }
    } catch (e) {
      setState("error");
      setError(e instanceof Error ? e.message : "上传失败");
    }
  }, [file]);

  if (!open) return null;

  return (
    <AnimatePresence>
      <motion.div
        className="fum-overlay"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        onClick={handleClose}
      >
        <motion.div
          className="fum-card"
          initial={{ opacity: 0, y: 30, scale: 0.95 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0, y: 30, scale: 0.95 }}
          transition={{ duration: 0.25 }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="fum-header">
            <h2>上传文件到知识库</h2>
            <button type="button" className="fum-close" onClick={handleClose}>
              <X size={18} />
            </button>
          </div>

          <div className="fum-body">
            {state === "idle" && (
              <>
                <div
                  className={`fum-dropzone ${dragActive ? "active" : ""} ${file ? "has-file" : ""}`}
                  onDrop={handleDrop}
                  onDragOver={handleDragOver}
                  onDragLeave={handleDragLeave}
                  onClick={() => inputRef.current?.click()}
                >
                  <input
                    ref={inputRef}
                    type="file"
                    className="fum-file-input"
                    accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.html,.csv"
                    onChange={(e) => {
                      const f = e.target.files?.[0];
                      if (f) handleFile(f);
                    }}
                  />
                  {file ? (
                    <>
                      <FileText size={32} className="fum-file-icon" />
                      <div className="fum-file-name">{file.name}</div>
                      <div className="fum-file-size">
                        {(file.size / 1024).toFixed(1)} KB
                      </div>
                    </>
                  ) : (
                    <>
                      <CloudUpload size={32} className="fum-upload-icon" />
                      <div className="fum-drop-text">
                        拖拽文件到此处，或点击选择
                      </div>
                      <div className="fum-supported">
                        支持 PDF、Word、Excel、PPT、TXT、Markdown、HTML
                      </div>
                    </>
                  )}
                </div>

                {error && (
                  <div className="fum-error">
                    <AlertCircle size={14} />
                    <span>{error}</span>
                  </div>
                )}
              </>
            )}

            {(state === "uploading" || state === "parsing") && (
              <div className="fum-progress-area">
                <div className="fum-file-info">
                  <FileText size={20} />
                  <span className="fum-file-name">{file?.name}</span>
                </div>

                <div className="fum-progress-steps">
                  <ProgressStep
                    label="上传中"
                    active={state === "uploading"}
                    done={state !== "uploading"}
                    percent={state === "uploading" ? progress : 100}
                  />
                  <div className="fum-step-connector" />
                  <ProgressStep
                    label="解析中"
                    active={state === "parsing"}
                    done={false}
                    statusText={parseStatus ?? undefined}
                  />
                </div>
              </div>
            )}

            {state === "done" && (
              <div className="fum-success">
                <motion.div
                  initial={{ scale: 0 }}
                  animate={{ scale: 1 }}
                  transition={{ type: "spring", stiffness: 300, damping: 20 }}
                >
                  <CheckCircle size={48} className="fum-success-icon" />
                </motion.div>
                <div className="fum-success-text">上传成功并已索引</div>
                <div className="fum-success-file">{file?.name}</div>
              </div>
            )}

            {state === "error" && (
              <div className="fum-error-state">
                <AlertCircle size={48} className="fum-error-icon" />
                <div className="fum-error-text">{error ?? "上传失败"}</div>
              </div>
            )}
          </div>

          <div className="fum-footer">
            {state === "idle" && (
              <>
                <button type="button" className="fum-btn secondary" onClick={handleClose}>
                  取消
                </button>
                <motion.button
                  type="button"
                  className="fum-btn primary"
                  onClick={handleUpload}
                  disabled={!file}
                  whileHover={{ y: -1 }}
                  whileTap={{ scale: 0.97 }}
                >
                  <Upload size={16} />
                  上传
                </motion.button>
              </>
            )}
            {(state === "uploading" || state === "parsing") && (
              <div className="fum-progress-hint">
                {state === "uploading"
                  ? `上传中 ${progress}%`
                  : `解析状态: ${parseStatus ?? "处理中"}`}
              </div>
            )}
            {(state === "done" || state === "error") && (
              <>
                <button
                  type="button"
                  className="fum-btn secondary"
                  onClick={state === "done" ? handleClose : reset}
                >
                  {state === "done" ? "关闭" : "重试"}
                </button>
                {state === "done" && (
                  <button type="button" className="fum-btn primary" onClick={reset}>
                    继续上传
                  </button>
                )}
              </>
            )}
          </div>
        </motion.div>
      </motion.div>
    </AnimatePresence>
  );
}

function ProgressStep({
  label,
  active,
  done,
  percent,
  statusText,
}: {
  label: string;
  active: boolean;
  done: boolean;
  percent?: number;
  statusText?: string;
}) {
  return (
    <div className={`fum-step ${active ? "active" : ""} ${done ? "done" : ""}`}>
      <div className="fum-step-dot">
        {done ? (
          <CheckCircle size={14} />
        ) : active ? (
          <Loader2 size={14} className="fum-spin" />
        ) : (
          <div className="fum-step-dot-inner" />
        )}
      </div>
      <div className="fum-step-label">{label}</div>
      {percent !== undefined && active && (
        <div className="fum-step-percent">{percent}%</div>
      )}
      {statusText && (
        <div className="fum-step-status">{statusText}</div>
      )}
    </div>
  );
}

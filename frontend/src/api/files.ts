export interface UploadResponse {
  uploadId: string;
  sourceName: string;
  objectKey: string;
  chunksIngested: number;
}

export interface UploadStatusResponse {
  uploadId: string;
  uploaded: number[];
  missing: number[];
  complete: boolean;
  parseStatus: string | null;
}

export type UploadProgressCallback = (percent: number) => void;

/**
 * Upload a file with progress tracking.
 * Uses XMLHttpRequest for progress events (fetch doesn't support upload progress).
 */
export function uploadFile(
  file: File,
  onProgress?: UploadProgressCallback,
): Promise<UploadResponse> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();

    xhr.upload.addEventListener("progress", (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    });

    xhr.addEventListener("load", () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText));
        } catch {
          reject(new Error("Invalid response"));
        }
      } else {
        try {
          const err = JSON.parse(xhr.responseText);
          reject(new Error(err.message || `Upload failed: ${xhr.status}`));
        } catch {
          reject(new Error(`Upload failed: ${xhr.status}`));
        }
      }
    });

    xhr.addEventListener("error", () => reject(new Error("Network error")));
    xhr.addEventListener("abort", () => reject(new Error("Upload aborted")));

    const formData = new FormData();
    formData.append("file", file);

    xhr.open("POST", "/api/files/upload");
    xhr.send(formData);
  });
}

/**
 * Poll upload status until complete or failed.
 */
export async function pollUploadStatus(
  uploadId: string,
  onStatusChange?: (status: UploadStatusResponse) => void,
  maxAttempts = 60,
  intervalMs = 2000,
): Promise<UploadStatusResponse> {
  for (let i = 0; i < maxAttempts; i++) {
    const resp = await fetch(`/api/files/status/${uploadId}`);
    if (!resp.ok) throw new Error(`Status check failed: ${resp.status}`);

    const status: UploadStatusResponse = await resp.json();
    onStatusChange?.(status);

    if (status.complete || status.parseStatus === "INDEXED" || status.parseStatus === "FAILED") {
      return status;
    }

    await new Promise((r) => setTimeout(r, intervalMs));
  }

  throw new Error("Status polling timeout");
}

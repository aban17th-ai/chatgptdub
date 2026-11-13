
"""Persian Dub AI backend. FFmpeg is required."""
import asyncio
import os
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path
from typing import Optional

import edge_tts
from fastapi import FastAPI, UploadFile, File, Form, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from google import genai
from google.genai import types
from faster_whisper import WhisperModel

APP_NAME = "Persian Dub AI API"
DATA_DIR = Path(os.getenv("DATA_DIR", "/tmp/persian-dub"))
UPLOAD_DIR = DATA_DIR / "uploads"
OUTPUT_DIR = DATA_DIR / "outputs"

for directory in (UPLOAD_DIR, OUTPUT_DIR):
    directory.mkdir(parents=True, exist_ok=True)

MAX_UPLOAD_MB = int(os.getenv("MAX_UPLOAD_MB", "500"))
SERVER_GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")
TEXT_MODEL = os.getenv("GEMINI_TEXT_MODEL", "gemini-2.5-flash")
WHISPER_MODEL = os.getenv("WHISPER_MODEL", "small")
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
WHISPER_COMPUTE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
ENABLE_DEMUCS = os.getenv("ENABLE_DEMUCS", "false").lower() == "true"
ENABLE_DIARIZATION = os.getenv("ENABLE_DIARIZATION", "false").lower() == "true"
HF_TOKEN = os.getenv("HF_TOKEN")

app = FastAPI(title=APP_NAME, version="0.5.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["*"]
)

jobs: dict[str, dict] = {}
_whisper = None


class JobStatus(BaseModel):
    job_id: str
    status: str
    progress: int
    message: str
    output_url: Optional[str] = None


class TranslateRequest(BaseModel):
    text: str
    source_language: str = "auto"
    target_language: str = "Persian"
    context: str = "Natural spoken dialogue. Preserve meaning, tone and intent."
    api_key: Optional[str] = None


class GeminiKeyRequest(BaseModel):
    api_key: str = Field(min_length=20, max_length=256)


def get_gemini_error(exc: Exception) -> tuple[int, str]:
    """Return a safe, user-friendly Gemini error without exposing secrets."""
    message = str(exc).lower()

    if any(x in message for x in ("api_key_invalid", "invalid api key",
                                  "unauthorized", "permission denied", "401", "403")):
        return 401, "کلید نامعتبر است یا اجازه دسترسی به Gemini ندارد."

    if any(x in message for x in ("429", "quota", "resource_exhausted",
                                  "rate limit", "too many requests")):
        return 429, "سهمیه یا محدودیت درخواست Gemini فعال شده است. بعداً دوباره امتحان کنید."

    if any(x in message for x in ("404", "not found", "model not found")):
        return 400, "مدل Gemini در دسترس نیست یا نام مدل پشتیبانی نمی‌شود."

    if any(x in message for x in ("timeout", "connection", "network")):
        return 503, "ارتباط با سرور Gemini برقرار نشد. اتصال اینترنت سرور را بررسی کنید."

    return 502, "Gemini درخواست را نپذیرفت. تنظیمات کلید و دسترسی حساب را بررسی کنید."


def translate_text(
    text: str,
    source_language: str,
    target_language: str = "Persian",
    api_key: Optional[str] = None
) -> str:
    key = api_key or SERVER_GEMINI_API_KEY

    if not key:
        raise RuntimeError("کلید Gemini وارد نشده است.")

    client = genai.Client(api_key=key)

    prompt = (
        f"Translate dialogue into natural, conversational {target_language} "
        "for dubbing. Keep it concise enough to fit the original timing. "
        "Preserve names, facts, emotion and speaker intent. "
        "Return only the translated dialogue.\n"
        f"Source language: {source_language}\nDialogue: {text}"
    )

    response = client.models.generate_content(
        model=TEXT_MODEL,
        contents=prompt,
        config=types.GenerateContentConfig(temperature=0.25)
    )

    translated = (response.text or "").strip()

    if not translated:
        raise RuntimeError("مدل ترجمه متن خالی برگرداند.")

    return translated


@app.get("/health")
def health():
    return {
        "ok": True,
        "service": APP_NAME,
        "version": "0.5.0",
        "server_gemini_configured": bool(SERVER_GEMINI_API_KEY),
        "gemini_user_key_supported": True,
        "ffmpeg_available": shutil.which("ffmpeg") is not None,
        "demucs_enabled": ENABLE_DEMUCS and shutil.which("demucs") is not None,
        "diarization_enabled": ENABLE_DIARIZATION and bool(HF_TOKEN)
    }


@app.post("/v1/gemini/validate")
def validate_gemini_key(req: GeminiKeyRequest):
    """Test a user's Gemini API key without saving or returning it."""
    try:
        client = genai.Client(api_key=req.api_key)
        response = client.models.generate_content(
            model=TEXT_MODEL,
            contents="Reply with exactly: OK",
            config=types.GenerateContentConfig(
                temperature=0,
                max_output_tokens=10
            )
        )

        if not (response.text or "").strip():
            return {
                "valid": False,
                "status": "empty_response",
                "message": "پاسخی از Gemini دریافت نشد."
            }

        return {
            "valid": True,
            "status": "success",
            "model": TEXT_MODEL,
            "message": "کلید Gemini معتبر است و اتصال با موفقیت برقرار شد."
        }

    except Exception as exc:
        status_code, message = get_gemini_error(exc)
        return {
            "valid": False,
            "status": "error",
            "error_code": status_code,
            "message": message
        }


def run_cmd(args: list[str], timeout: int = 3600):
    proc = subprocess.run(
        args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout
    )

    if proc.returncode != 0:
        tail = (proc.stderr or proc.stdout)[-1800:]
        raise RuntimeError(f"فرمان پردازش ناموفق بود: {tail}")

    return proc


async def make_tts(text: str, voice: str, destination: Path):
    communicate = edge_tts.Communicate(
        text=text,
        voice=voice,
        rate="+0%",
        volume="+0%"
    )
    await communicate.save(str(destination))


def get_whisper():
    global _whisper

    if _whisper is None:
        _whisper = WhisperModel(
            WHISPER_MODEL,
            device=WHISPER_DEVICE,
            compute_type=WHISPER_COMPUTE
        )

    return _whisper


def separate_background(input_audio: Path, work_dir: Path) -> tuple[Path, str]:
    if not ENABLE_DEMUCS:
        return input_audio, "جداسازی موسیقی خاموش است؛ صدای اصلی با دوبله ترکیب می‌شود."

    if not shutil.which("demucs"):
        return input_audio, "Demucs روی سرور نصب نیست؛ صدای اصلی استفاده می‌شود."

    try:
        run_cmd([
            "demucs", "--two-stems=vocals",
            "-n", "htdemucs", "-o", str(work_dir), str(input_audio)
        ], timeout=14400)

        candidates = list(work_dir.glob("**/no_vocals.wav"))

        if candidates:
            return candidates[0], "موسیقی و افکت‌ها از گفتار جدا شدند."

    except Exception as exc:
        return input_audio, f"جداسازی ناموفق بود: {type(exc).__name__}"

    return input_audio, "خروجی جداسازی پیدا نشد؛ صدای اصلی استفاده شد."


def diarize_segments(
    audio_path: Path,
    segments: list[dict]
) -> tuple[list[dict], dict[str, str], str]:

    if not ENABLE_DIARIZATION:
        return segments, {}, "تفکیک گویندگان خاموش است."

    if not HF_TOKEN:
        return segments, {}, "توکن HF_TOKEN تنظیم نشده است."

    try:
        from pyannote.audio import Pipeline
        import torch

        pipeline = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-3.1",
            use_auth_token=HF_TOKEN
        )

        if pipeline is None:
            raise RuntimeError("بارگذاری مدل pyannote انجام نشد.")

        pipeline.to(torch.device(
            "cuda" if torch.cuda.is_available() else "cpu"
        ))

        diarization = pipeline(str(audio_path))

        if hasattr(diarization, "speaker_diarization"):
            diarization = diarization.speaker_diarization

        turns = []
        first_seen = {}

        for turn, _, speaker in diarization.itertracks(yield_label=True):
            speaker = str(speaker)
            turns.append((float(turn.start), float(turn.end), speaker))
            first_seen[speaker] = min(
                first_seen.get(speaker, float(turn.start)),
                float(turn.start)
            )

        ordered = sorted(first_seen, key=first_seen.get)

        voice_pool = [
            "fa-IR-DilaraNeural",
            "fa-IR-FaridNeural"
        ]

        voice_map = {
            speaker: voice_pool[i % len(voice_pool)]
            for i, speaker in enumerate(ordered)
        }

        assigned = []

        for seg in segments:
            overlaps = {}

            for start, end, speaker in turns:
                overlap = max(
                    0.0,
                    min(seg["end"], end) - max(seg["start"], start)
                )
                if overlap:
                    overlaps[speaker] = overlaps.get(speaker, 0.0) + overlap

            item = dict(seg)
            item["speaker"] = (
                max(overlaps, key=overlaps.get)
                if overlaps else (ordered[0] if ordered else None)
            )
            assigned.append(item)

        return (
            assigned,
            voice_map,
            f"{len(ordered)} گوینده شناسایی شد و صداهای جداگانه تخصیص یافت."
        )

    except Exception as exc:
        return (
            segments,
            {},
            f"تفکیک گویندگان انجام نشد: {type(exc).__name__}: {str(exc)[:180]}"
        )


def build_timeline(clips: list[tuple[Path, float]], output: Path, duration: float):
    if not clips:
        raise RuntimeError("هیچ قطعه صدای دوبله تولید نشد.")

    inputs = []
    filters = []

    for index, (clip, start) in enumerate(clips):
        inputs += ["-i", str(clip)]
        delay = max(0, int(start * 1000))
        filters.append(
            f"[{index}:a]adelay={delay}|{delay},volume=1.0[a{index}]"
        )

    labels = "".join(f"[a{i}]" for i in range(len(clips)))

    filters.append(
        f"{labels}amix=inputs={len(clips)}:duration=longest:"
        f"normalize=0,atrim=0:{duration:.3f}[dub]"
    )

    run_cmd([
        "ffmpeg", "-y", *inputs,
        "-filter_complex", ";".join(filters),
        "-map", "[dub]",
        "-c:a", "libmp3lame",
        "-q:a", "3",
        str(output)
    ])


def process_job(job_id: str):
    job = jobs[job_id]
    source = Path(job["path"])
    api_key = job.pop("gemini_api_key", None)

    work = Path(tempfile.mkdtemp(
        prefix=f"dub_{job_id}_",
        dir=str(DATA_DIR)
    ))

    try:
        if not shutil.which("ffmpeg"):
            raise RuntimeError("FFmpeg روی سرور نصب نیست.")

        job.update(
            status="processing",
            progress=10,
            message="در حال استخراج صدای فایل..."
        )

        extracted = work / "source.wav"

        run_cmd([
            "ffmpeg", "-y", "-i", str(source),
            "-vn", "-ac", "2", "-ar", "44100",
            str(extracted)
        ])

        job.update(
            progress=18,
            message="در حال جداسازی موسیقی و افکت‌ها..."
        )

        background, background_note = separate_background(extracted, work)

        job.update(
            progress=25,
            message="در حال تشخیص گفتار و زمان‌بندی جمله‌ها..."
        )

        model = get_whisper()
        segments_iter, info = model.transcribe(
            str(extracted),
            beam_size=5,
            vad_filter=True
        )

        segments = [
            {
                "start": float(s.start),
                "end": float(s.end),
                "text": s.text.strip()
            }
            for s in segments_iter if s.text.strip()
        ]

        if not segments:
            raise RuntimeError("گفتاری در فایل پیدا نشد.")

        duration_proc = run_cmd([
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(source)
        ])

        duration = float(duration_proc.stdout.strip())
        source_lang = getattr(info, "language", "auto")
        target_lang = job.get("target_language", "fa")

        speaker_note = ""

        if job.get("separate_speakers", False):
            job.update(
                progress=30,
                message="در حال تشخیص هویت گویندگان..."
            )

            segments, speaker_voices, speaker_note = diarize_segments(
                extracted, segments
            )
        else:
            speaker_voices = {}
            speaker_note = "تفکیک گویندگان از تنظیمات برنامه خاموش است."

        base_voice = job.get("voice", "fa-IR-DilaraNeural")
        clips = []

        job.update(
            progress=34,
            message=f"{len(segments)} بخش گفتار شناسایی شد؛ ترجمه و تولید صدا آغاز شد..."
        )

        for index, segment in enumerate(segments):
            translated = translate_text(
                segment["text"],
                source_lang,
                target_lang,
                api_key
            )

            clip_path = work / f"line_{index:04d}.mp3"

            selected_voice = speaker_voices.get(
                segment.get("speaker"),
                base_voice
            )

            asyncio.run(make_tts(translated, selected_voice, clip_path))
            clips.append((clip_path, segment["start"]))

            progress = 32 + int(48 * (index + 1) / len(segments))

            job.update(
                progress=progress,
                message=f"در حال دوبله جمله {index + 1} از {len(segments)}..."
            )

        job.update(
            progress=83,
            message="در حال چیدن صدا روی زمان‌بندی ویدئو..."
        )

        dubbed = work / "dubbed.mp3"
        build_timeline(clips, dubbed, duration)

        final_path = OUTPUT_DIR / f"{job_id}_persian_dub.mp4"

        job.update(
            progress=91,
            message="در حال ساخت ویدئوی نهایی MP4..."
        )

        mux_inputs = [
            "-i", str(source),
            "-i", str(background),
            "-i", str(dubbed)
        ]

        if job.get("preserve_background", True):
            filter_graph = (
                "[1:a]volume=0.35[bed];"
                "[bed][2:a]amix=inputs=2:duration=first:"
                "weights=1 1[aout]"
            )
        else:
            filter_graph = "[2:a]anull[aout]"

        run_cmd([
            "ffmpeg", "-y", *mux_inputs,
            "-filter_complex", filter_graph,
            "-map", "0:v?",
            "-map", "[aout]",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "192k",
            "-shortest",
            str(final_path)
        ], timeout=7200)

        job.update(
            status="completed",
            progress=100,
            message=f"دوبله تمام شد. {speaker_note} {background_note}",
            output_path=str(final_path),
            output_url=f"/v1/jobs/{job_id}/download"
        )

    except Exception as exc:
        job.update(
            status="failed",
            progress=job.get("progress", 0),
            message=f"پردازش ناموفق بود: {str(exc)[:900]}"
        )

    finally:
        shutil.rmtree(work, ignore_errors=True)
        source.unlink(missing_ok=True)


@app.post("/v1/jobs", response_model=JobStatus)
async def create_job(
    background_tasks: BackgroundTasks,
    media: Optional[UploadFile] = File(default=None),
    source_url: Optional[str] = Form(default=None),
    target_language: str = Form(default="fa"),
    preserve_background: bool = Form(default=True),
    separate_speakers: bool = Form(default=True),
    voice: str = Form(default="fa-IR-DilaraNeural"),
    gemini_api_key: Optional[str] = Form(default=None)
):
    if media is None and not source_url:
        raise HTTPException(400, "یک فایل یا لینک منبع لازم است.")

    if source_url:
        raise HTTPException(
            501,
            "پردازش لینک هنوز فعال نیست؛ فعلاً فایل مجاز را آپلود کنید."
        )

    if not (gemini_api_key or SERVER_GEMINI_API_KEY):
        raise HTTPException(
            400,
            "کلید Gemini وارد نشده است. ابتدا کلید خود را در برنامه ثبت کنید."
        )

    job_id = str(uuid.uuid4())
    safe_name = Path(media.filename or "upload.bin").name.replace("..", "_")
    saved = UPLOAD_DIR / f"{job_id}_{safe_name}"
    size = 0

    try:
        with saved.open("wb") as out:
            while chunk := await media.read(1024 * 1024):
                size += len(chunk)

                if size > MAX_UPLOAD_MB * 1024 * 1024:
                    saved.unlink(missing_ok=True)
                    raise HTTPException(
                        413,
                        f"حداکثر حجم فایل {MAX_UPLOAD_MB} مگابایت است."
                    )

                out.write(chunk)
    finally:
        await media.close()

    jobs[job_id] = {
        "job_id": job_id,
        "status": "queued",
        "progress": 3,
        "message": "فایل دریافت شد و در صف پردازش قرار گرفت.",
        "path": str(saved),
        "target_language": target_language,
        "preserve_background": preserve_background,
        "separate_speakers": separate_speakers,
        "voice": voice,
        "gemini_api_key": gemini_api_key
    }

    background_tasks.add_task(process_job, job_id)

    return JobStatus(
        job_id=job_id,
        status="queued",
        progress=3,
        message="فایل دریافت شد و در صف پردازش قرار گرفت."
    )


@app.get("/v1/jobs/{job_id}", response_model=JobStatus)
def get_job(job_id: str):
    job = jobs.get(job_id)

    if not job:
        raise HTTPException(404, "شناسه پردازش پیدا نشد.")

    return JobStatus(
        job_id=job_id,
        status=job["status"],
        progress=job["progress"],
        message=job["message"],
        output_url=job.get("output_url")
    )


@app.get("/v1/jobs/{job_id}/download")
def download_job(job_id: str):
    job = jobs.get(job_id)

    if not job or job.get("status") != "completed":
        raise HTTPException(404, "فایل خروجی هنوز آماده نیست.")

    path = Path(job["output_path"])

    if not path.exists():
        raise HTTPException(404, "فایل خروجی دیگر در دسترس نیست.")

    return FileResponse(
        path,
        media_type="video/mp4",
        filename="persian_dub.mp4"
    )


@app.post("/v1/translate")
def translate(req: TranslateRequest):
    try:
        return {
            "translation": translate_text(
                req.text,
                req.source_language,
                req.target_language,
                req.api_key
            )
        }
    except Exception as exc:
        status_code, message = get_gemini_error(exc)
        raise HTTPException(status_code, f"خطا در ترجمه: {message}")

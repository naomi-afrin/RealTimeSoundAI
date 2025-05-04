# sound_classifier.py
import librosa
import numpy as np
from skimage.transform import resize

MODEL_PATH = "sound_classifier_2_17.tflite"
LABELS = ["other", "car horn", "scream", "dog bark"]
N_MELS = 128
IMG_SIZE = (128, 128)
SR = 22050



# === Preprocess audio into mel spectrogram ===
def preprocess_audio(array_1d):
    print("Raw audio max:", max(array_1d))
    print("Raw audio min:", min(array_1d))
    print("Recorded audio shape:", array_1d.shape)

    print("in preprocess audio")
    print(type(array_1d))
    array_1d = np.array(array_1d)
    print(type(array_1d))
    print("nparray_1d max:", max(array_1d))
    print("nparray_1d min:", min(array_1d))


    # Ensure audio is exactly 22050 samples (3 seconds of audio at 22050 Hz)
    target_length = SR * 3  # 3 seconds
    if len(array_1d) > target_length:
        array_1d = array_1d[:target_length]  # Trim excess audio
    elif len(array_1d) < target_length:
        # Pad with zeros if the audio is shorter than 3 seconds
        padding = target_length - len(array_1d)
        array_1d = np.pad(array_1d, (0, padding), mode='constant', constant_values=0)

    mel_spectrogram = librosa.feature.melspectrogram(y=array_1d, sr=SR, n_mels=N_MELS)
    print("converted mel spectogram")
    mel_spectrogram_db = librosa.power_to_db(mel_spectrogram, ref=np.max)
    print("converted db")
    # Resize correctly without distortion
    mel_spectrogram_resized = resize(mel_spectrogram_db, IMG_SIZE, anti_aliasing=True)
    print("resized")
    input_tensor = mel_spectrogram_resized.reshape(1, IMG_SIZE[0], IMG_SIZE[1], 1).astype(np.float32)
    print("reshaped")
    return input_tensor




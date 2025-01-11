import os
import subprocess
import sys

def install_package(package_name):
    try:
        subprocess.check_call([sys.executable, "-m", "pip", "install", package_name])
    except subprocess.CalledProcessError:
        print(f"Failed to install {package_name}")
        sys.exit(1)

def setup_paddleocr():
    try:
        import paddleocr
        import fitz
        print("PaddleOCR is already installed.")
    except ImportError:
        print("PaddleOCR not found. Installing...")
        install_package("pymupdf")
        install_package("paddleocr")
        install_package("paddlepaddle")  # Ensure PaddlePaddle backend is installed
        print("PaddleOCR installation complete.")

if __name__ == "__main__":
    setup_paddleocr()
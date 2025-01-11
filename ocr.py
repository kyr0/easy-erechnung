import json
import sys
from paddleocr import PaddleOCR

def run_paddleocr(image_path, output_path):
    # Initialize PaddleOCR
    ocr = PaddleOCR(use_angle_cls=True, lang='de')

    # Perform OCR
    results = ocr.ocr(image_path, cls=True)

    # Structure results in JSON format
    structured_data = []
    for line in results[0]:  # Iterate over detected text lines
        structured_data.append({
            "text": line[1][0],         # Extracted text
            "confidence": line[1][1],  # Confidence score
            "bounding_box": line[0]    # Bounding box coordinates
        })

    # Save JSON output
    with open(output_path, 'w') as f:
        json.dump(structured_data, f, indent=4, ensure_ascii=False)

    print(f"OCR results saved to {output_path}")

if __name__ == "__main__":
    # Command-line arguments: image_path and output_path
    if len(sys.argv) != 3:
        print("Usage: python paddleocr_to_json.py <image_path> <output_path>")
        sys.exit(1)

    image_path = sys.argv[1]
    output_path = sys.argv[2]

    run_paddleocr(image_path, output_path)
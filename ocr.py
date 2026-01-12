import sys
import os
import shutil
import fitz  # PyMuPDF
from paddleocr import PaddleOCRVL

def pdf_to_pngs(pdf_path, temp_dir):
    if not os.path.exists(temp_dir):
        os.makedirs(temp_dir)
        
    doc = fitz.open(pdf_path)
    image_paths = []
    for i, page in enumerate(doc):
        # DPI 200 is usually a good balance for OCR
        pix = page.get_pixmap(dpi=200)
        image_path = os.path.join(temp_dir, f"page_{i+1:03d}.png")
        pix.save(image_path)
        image_paths.append(image_path)
    return image_paths

def run_paddleocr(input_path, output_path):
    # Initialize PaddleOCR VL
    pipeline = PaddleOCRVL()

    # Determine output directory
    if os.path.splitext(output_path)[1]:
        output_dir = os.path.dirname(output_path)
    else:
        output_dir = output_path
    
    if not output_dir:
        output_dir = "."
    
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    # Handle Input (PDF vs Image)
    temp_dir = "temp_ocr_processing_pages"
    image_paths = []
    is_pdf = input_path.lower().endswith('.pdf')

    # Collect all results
    all_results = []
    all_markdown_content = []
    
    md_temp_dir = "temp_md_processing"
    
    try:
        if is_pdf:
            print(f"Detected PDF input. Converting {input_path} to images...")
            image_paths = pdf_to_pngs(input_path, temp_dir)
        else:
            image_paths = [input_path]

        # Perform OCR on each image
        for i, img_path in enumerate(image_paths):
            print(f"Processing image: {img_path}")
            output = pipeline.predict(img_path)

            for res in output:
                res.print()
                
                # Collect JSON result
                if hasattr(res, 'json'):
                     all_results.append(res.json)
                else:
                     all_results.append(str(res))
                
                # Collect Markdown content
                # Create a fresh temp dir for this page to avoid filename collisions
                current_page_md_dir = os.path.join(md_temp_dir, f"page_{i}")
                if not os.path.exists(current_page_md_dir):
                    os.makedirs(current_page_md_dir)
                
                try:
                    res.save_to_markdown(save_path=current_page_md_dir)
                    # Find the generated .md file
                    for f_name in os.listdir(current_page_md_dir):
                        if f_name.endswith(".md"):
                            with open(os.path.join(current_page_md_dir, f_name), 'r', encoding='utf-8') as md_f:
                                all_markdown_content.append(md_f.read())
                except Exception as e:
                    print(f"Warning: Could not extract markdown for page {i}: {e}")
                finally:
                    # Clean up this page's temp md dir
                    if os.path.exists(current_page_md_dir):
                        shutil.rmtree(current_page_md_dir)

    finally:
        # Cleanup temporary PDF pages
        if is_pdf and os.path.exists(temp_dir):
            shutil.rmtree(temp_dir)
        # Cleanup temp md parent dir
        if os.path.exists(md_temp_dir):
            shutil.rmtree(md_temp_dir)

    # Save Merged JSON to the exact output_path
    import json
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(all_results, f, indent=4, ensure_ascii=False)
        
    print(f"OCR results saved to: {output_path}")
    
    # Save Merged Markdown
    base_output = os.path.splitext(output_path)[0]
    md_output_path = base_output + ".md"
    
    with open(md_output_path, 'w', encoding='utf-8') as f:
        f.write("\n\n---\n\n".join(all_markdown_content))
        
    print(f"OCR markdown saved to: {md_output_path}")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python ocr.py <image_or_pdf_path> <output_path>")
        sys.exit(1)

    input_path = sys.argv[1]
    output_path = sys.argv[2]

    run_paddleocr(input_path, output_path)
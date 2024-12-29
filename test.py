from deepface import DeepFace
import tensorflow as tf
import os
import cv2
import pandas as pd

# Suppress TensorFlow warnings
os.environ['TF_ENABLE_ONEDNN_OPTS'] = '0'
tf.get_logger().setLevel('ERROR')

# OpenCV to capture image from webcam
def capture_image_from_camera():
    # Open the webcam
    cap = cv2.VideoCapture(0)  # 0 is the default camera

    # Check if the camera is opened
    if not cap.isOpened():
        print("Error: Could not open webcam.")
        return None
    
    print("Press 'q' to capture the image.")
    
    while True:
        ret, frame = cap.read()
        if not ret:
            print("Error: Failed to grab frame.")
            break

        # Display the captured frame
        cv2.imshow("Press 'q' to capture", frame)

        # Wait for 'q' key to be pressed to capture the image
        if cv2.waitKey(1) & 0xFF == ord('q'):
            image_path = "captured_image.jpg"  # Save the captured image
            cv2.imwrite(image_path, frame)
            print(f"Image captured and saved as {image_path}")
            break

    # Release the webcam and close OpenCV windows
    cap.release()
    cv2.destroyAllWindows()

    return image_path


# Capture image from the camera
captured_image_path = capture_image_from_camera()

# If image captured successfully, proceed with face recognition
if captured_image_path:
    # Database path
    db_path = r"C:\Users\attiullah.khanniazi\Downloads\facial_db"

    # Perform face recognition
    recognition = DeepFace.find(img_path=captured_image_path, db_path=db_path)

    # Since recognition is a list of DataFrames, we need to access the first element
    if isinstance(recognition, list) and len(recognition) > 0:
        recognition_df = recognition[0]  # The first DataFrame in the list

        # Check if 'identity' column exists in the DataFrame
        if 'identity' in recognition_df.columns and not recognition_df.empty:
            # Extracting image names without extensions
            recognition_df['image_name'] = recognition_df['identity'].apply(lambda x: os.path.splitext(os.path.basename(x))[0])

            # Print only the first matching image name
            first_match = recognition_df['image_name'].iloc[0]  # Get the first match
            print(f"Matching image name: {first_match}")
        else:
            print("\nNo identity column found or no matches found.")
    else:
        print("No recognition results found.")

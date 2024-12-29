import tensorflow as tf
import tensorflow_hub as hub
import numpy as np
import cv2
import matplotlib.pyplot as plt

# Load YOLOv4 model from TensorFlow Hub
MODEL_URL = 'https://tfhub.dev/tensorflow/yolov4/1'
model = hub.load(MODEL_URL)

# Load image using OpenCV
image_path = r"C:\Users\attiullah.khanniazi\Downloads\facial_db\Firdous.jpg"
img = cv2.imread(image_path)

# Convert BGR image to RGB
img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

# Resize image to match the input size expected by YOLOv4 (416x416)
input_size = 416
img_resized = cv2.resize(img_rgb, (input_size, input_size))

# Normalize image (YOLO expects values between 0 and 1)
img_resized = img_resized / 255.0

# Add batch dimension (YOLO expects batch size of 1)
input_data = np.expand_dims(img_resized, axis=0)

# Perform inference
output = model(input_data)

# Post-processing the output to extract bounding boxes, class labels, and confidence scores
boxes, class_probs, class_ids, confidences = output

# Print detections
for box, class_prob, class_id, confidence in zip(boxes[0], class_probs[0], class_ids[0], confidences[0]):
    if confidence > 0.5:  # Only consider detections with confidence > 0.5
        print(f"Class ID: {class_id}, Confidence: {confidence.numpy()}")
        print(f"Bounding box: {box.numpy()}")

# Show the image with bounding boxes
plt.imshow(img_rgb)
plt.show()

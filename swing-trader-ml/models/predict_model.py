import pandas as pd
import joblib
import sys
import json
import os
import pandas_ta as ta

# --- Configuration ---
MODEL_DIR = "models"
MODEL_FILE = "swing_trader_model_xgb.pkl"

def make_prediction():
    """
    Loads raw bar data from stdin, calculates features, loads the trained
    model, and makes a prediction on the latest data point.
    """
    # 1. Read the JSON data from standard input (passed from Java)
    input_json = sys.stdin.read()
    raw_data = json.loads(input_json)

    # 2. Convert raw data to a Pandas DataFrame
    df = pd.DataFrame(raw_data)
    df.rename(columns={'t': 'Date', 'o': 'Open', 'h': 'High', 'l': 'Low', 'c': 'Close', 'v': 'Volume'}, inplace=True)
    df['Date'] = pd.to_datetime(df['Date'])
    df.set_index('Date', inplace=True)

    # 3. Feature Engineering: Calculate all indicators, just like in training
    df.ta.sma(length=10, append=True)
    df.ta.sma(length=50, append=True)
    df.ta.rsi(length=14, append=True)
    df.ta.macd(append=True)
    df.ta.bbands(append=True)
    df.ta.atr(append=True)
    df.ta.adx(append=True)
    df.ta.obv(append=True)
    df.dropna(inplace=True)

    if df.empty:
        print(0) # Not enough data to make a prediction
        return

    # 4. Get the features for the most recent data point
    feature_cols = [
        'SMA_10', 'SMA_50', 'RSI_14', 'ATRr_14', 'OBV', 'ADX_14', 'DMP_14',
        'DMN_14', 'BBL_5_2.0', 'BBM_5_2.0', 'BBU_5_2.0', 'BBB_5_2.0',
        'BBP_5_2.0', 'MACD_12_26_9', 'MACDh_12_26_9', 'MACDs_12_26_9'
    ]
    latest_features = df[feature_cols].tail(1)

    # 5. Load the trained model
    model_path = os.path.join(MODEL_DIR, MODEL_FILE)
    if not os.path.exists(model_path):
        print(0) # Model not found
        return
    model = joblib.load(model_path)

    # 6. Make a prediction and print the result
    prediction = model.predict(latest_features)
    print(prediction[0])

if __name__ == '__main__':
    make_prediction()
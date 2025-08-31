import os
import pandas as pd
import pandas_ta as ta

# --- Configuration ---
WATCHLIST = ['SPY', 'BND', 'GLD', 'AAPL', 'MSFT', 'TSLA']
DATA_DIR = "data"
PROCESSED_DIR = "processed_data"

# --- Labeling Configuration ---
# How far into the future to look for a result
FORWARD_WINDOW = 20
# Profit target percentage
PROFIT_TARGET = 0.05  # 5%
# Stop loss percentage
STOP_LOSS = 0.02  # 2%


def process_stock_data():
    """
    Loads raw data, calculates features (indicators), creates a target label,
    and saves the processed data to a new CSV file.
    """
    print("--- Starting Data Processing ---")

    # Ensure the output directory exists
    if not os.path.exists(PROCESSED_DIR):
        os.makedirs(PROCESSED_DIR)

    for symbol in WATCHLIST:
        file_path = os.path.join(DATA_DIR, f"{symbol}.csv")
        if not os.path.exists(file_path):
            print(f"No data file for {symbol}, skipping.")
            continue

        print(f"Processing data for {symbol}...")

        # Load the raw data
        df = pd.read_csv(file_path, header=0, index_col=0, skiprows=[1, 2], parse_dates=True)
        df.index.name = 'Date'

        # --- 1. Feature Engineering ---
        # Calculate Simple Moving Averages (SMA) and RSI using pandas-ta
        df.ta.sma(length=10, append=True) # Adds 'SMA_10' column
        df.ta.sma(length=50, append=True) # Adds 'SMA_50' column
        df.ta.rsi(length=14, append=True) # Adds 'RSI_14' column

        # Add MACD, Bollinger Bands, and ATR as new features
        df.ta.macd(append=True)
        df.ta.bbands(append=True)
        df.ta.atr(append=True)

        # Add ADX and OBV
        df.ta.adx(append=True)
        df.ta.obv(append=True)

        # --- 2. Labeling ---
        # Create the target variable (1 if a good buy, 0 otherwise)
        df['target'] = 0
        for i in range(len(df) - FORWARD_WINDOW):
            entry_price = df['Close'][i]
            profit_price = entry_price * (1 + PROFIT_TARGET)
            loss_price = entry_price * (1 - STOP_LOSS)

            # Look forward in time from the current day
            for j in range(1, FORWARD_WINDOW + 1):
                future_price = df['Close'][i + j]
                # If profit target is hit first, label as 1 and break
                if future_price >= profit_price:
                    df.at[df.index[i], 'target'] = 1
                    break
                # If stop loss is hit first, label remains 0 and break
                if future_price <= loss_price:
                    break

        # --- 3. Clean and Save ---
        # Remove rows with NaN values (from the start of the indicator calculations)
        df.dropna(inplace=True)

        # Save the processed data to a new file
        processed_file_path = os.path.join(PROCESSED_DIR, f"{symbol}_processed.csv")
        df.to_csv(processed_file_path)
        print(f"Successfully processed and saved data for {symbol} to {processed_file_path}")

    print("--- Data Processing Complete ---")


if __name__ == '__main__':
    process_stock_data()
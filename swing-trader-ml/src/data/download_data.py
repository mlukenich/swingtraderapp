import os
import pandas as pd
import yfinance as yf
from datetime import date, timedelta

# --- Configuration ---
# You can now use a much wider variety of tickers from Yahoo Finance
WATCHLIST = ['SPY', 'BND', 'GLD', 'AAPL', 'MSFT', 'TSLA']
# Define the date range for the historical data (e.g., last 5 years)
END_DATE = date.today()
START_DATE = END_DATE - timedelta(days = 5 * 365)
# The directory to save the CSV files
DATA_DIR = "../../data"

def download_stock_data():
    """
    Downloads historical daily data for each stock in the watchlist
    using yfinance and saves it to a CSV file.
    """
    print("--- Starting Data Download using yfinance ---")

    # Ensure the data directory exists
    if not os.path.exists(DATA_DIR):
        os.makedirs(DATA_DIR)

    for symbol in WATCHLIST:
        print(f"Fetching data for {symbol} from {START_DATE} to {END_DATE}...")
        try:
            # Download the data using yfinance
            # The result is already a Pandas DataFrame
            df = yf.download(
                tickers=symbol,
                start=START_DATE,
                end=END_DATE,
                interval="1d" # "1d" for daily data
            )
            
            # If the DataFrame is not empty, save it to a CSV file
            if not df.empty:
                file_path = os.path.join(DATA_DIR, f"{symbol}.csv")
                df.to_csv(file_path)
                print(f"Successfully saved data for {symbol} to {file_path}")
            else:
                print(f"No data returned for {symbol}. It might be delisted or an invalid ticker.")

        except Exception as e:
            print(f"Could not fetch data for {symbol}: {e}")

    print("--- Data Download Complete ---")

if __name__ == '__main__':
    download_stock_data()
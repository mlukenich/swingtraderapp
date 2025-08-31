import os
import pandas as pd
import joblib
from sklearn.model_selection import train_test_split, GridSearchCV
from xgboost import XGBClassifier
from sklearn.metrics import classification_report
from imblearn.over_sampling import SMOTE # <-- Import SMOTE
import matplotlib.pyplot as plt

# --- Configuration ---
PROCESSED_DIR = "processed_data"
MODEL_DIR = "models"
TRAINING_SYMBOL = "AAPL"

def train_model():
    """
    Loads processed data, balances the training set using SMOTE, finds the
    best XGBoost model, evaluates it, and saves it.
    """
    print("--- Starting Model Training with SMOTE ---")

    # --- 1. Load Data ---
    file_path = os.path.join(PROCESSED_DIR, f"{TRAINING_SYMBOL}_processed.csv")
    if not os.path.exists(file_path):
        print(f"Error: Processed data file not found at {file_path}")
        return

    df = pd.read_csv(file_path, index_col='Date', parse_dates=True)
    print(f"Loaded {len(df)} rows of processed data for {TRAINING_SYMBOL}.")

    # --- 2. Define Features (X) and Target (y) ---
    adx_cols = [col for col in df.columns if 'ADX' in col]
    bbands_cols = [col for col in df.columns if 'BB' in col]
    macd_cols = [col for col in df.columns if 'MACD' in col]
    feature_cols = ['SMA_10', 'SMA_50', 'RSI_14', 'ATRr_14', 'OBV'] + adx_cols + bbands_cols + macd_cols
    X = df[feature_cols]
    y = df['target']

    # --- 3. Split Data ---
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, shuffle=False
    )
    print(f"Original training data shape: {X_train.shape}")
    print(f"Original training class distribution:\n{y_train.value_counts()}")

    # --- 4. Apply SMOTE to the Training Data ---
    # SMOTE should ONLY be applied to the training set, never the test set.
    print("\nApplying SMOTE to balance the training data...")
    smote = SMOTE(random_state=42)
    X_train_resampled, y_train_resampled = smote.fit_resample(X_train, y_train)
    print(f"Resampled training data shape: {X_train_resampled.shape}")
    print(f"Resampled training class distribution:\n{y_train_resampled.value_counts()}")


    # --- 5. Hyperparameter Tuning with XGBoost ---
    print("\nStarting GridSearchCV to find the best XGBoost parameters...")

    param_grid = {
        'n_estimators': [100, 200],
        'max_depth': [3, 5, 7],
        'learning_rate': [0.01, 0.1]
    }

    # Initialize the XGBoost model. We no longer need scale_pos_weight
    # because SMOTE has balanced the dataset for us.
    xgb = XGBClassifier(random_state=42, use_label_encoder=False, eval_metric='logloss')

    grid_search = GridSearchCV(estimator=xgb, param_grid=param_grid, cv=3, scoring='f1', n_jobs=-1, verbose=2)

    # Fit the model on the NEW, resampled data
    grid_search.fit(X_train_resampled, y_train_resampled)

    print("\nGridSearchCV complete.")
    print("Best parameters found: ", grid_search.best_params_)

    best_model = grid_search.best_estimator_

    # --- 6. Evaluate the Best Model on the ORIGINAL Test Set ---
    print("\n--- Best Model Evaluation (XGBoost with SMOTE) ---")
    predictions = best_model.predict(X_test)
    report = classification_report(y_test, predictions)
    print(report)

    # --- 7. Save the Best Model ---
    if not os.path.exists(MODEL_DIR):
        os.makedirs(MODEL_DIR)

    model_path = os.path.join(MODEL_DIR, "swing_trader_model_xgb_smote.pkl")
    joblib.dump(best_model, model_path)
    print(f"\nBest model saved successfully to {model_path}")

    # --- 8. Analyze and Plot Feature Importance ---
    print("\n--- Feature Importances ---")
    importances = pd.Series(best_model.feature_importances_, index=feature_cols).sort_values()
    print(importances)
    plt.figure(figsize=(10, 8))
    importances.plot(kind='barh', color='cyan')
    plt.title(f'Feature Importances for {TRAINING_SYMBOL} Model (SMOTE)')
    plt.xlabel('Importance Score')
    plt.tight_layout()
    plot_path = os.path.join(MODEL_DIR, "feature_importance_smote.png")
    plt.savefig(plot_path)
    print(f"\nFeature importance plot saved to {plot_path}")

if __name__ == '__main__':
    train_model()
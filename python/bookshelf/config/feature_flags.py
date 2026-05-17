from pydantic_settings import BaseSettings


class FeatureFlags(BaseSettings):
    loan_extension_enabled: bool = False

    model_config = {"env_prefix": "FEATURES_"}

def get_metadata(file_path):
    import sgram_engine
    with open(file_path, "r", encoding="utf-8-sig") as f:
        return sgram_engine.parse_metadata(f.read())

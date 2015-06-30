package edu.berkeley.cspace.pictionbridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class DatabaseUpdateMonitor implements UpdateMonitor {
	private static final Logger logger = LogManager.getLogger(DatabaseUpdateMonitor.class);
	
	private static final String BINARY_DIR = "binaries";
	
	private String workPath;
	private String interfaceTable;
	
	private DataSource dataSource;
	private JdbcTemplate jdbcTemplate;
	
	public DatabaseUpdateMonitor() {				

	}
	
	private void createWorkDirectories() {
		Path binaryPath = FileSystems.getDefault().getPath(getWorkPath(), BINARY_DIR);
		
		try {
			Files.createDirectories(binaryPath);
		} catch (IOException e) {
			logger.fatal("failed to create work directory " + binaryPath, e);
			
			throw(new UpdateMonitorException(e));
		}
	}

	public boolean hasUpdates() {
		return (getUpdateCount() > 0);
	}

	public int getUpdateCount() {
		return this.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + getInterfaceTable(), Integer.class);
	}

	public List<Update> getUpdates() {
		return getUpdates(null);
	}
	
	public List<Update> getUpdates(Integer limit) {
		String sql = "SELECT id, piction_id, filename, mimetype, img_size, img_height, img_width, object_csid, media_csid, blob_csid, action, relationship, dt_addedtopiction, dt_uploaded, bimage FROM " + getInterfaceTable() + " ORDER BY dt_uploaded";
		
		if (limit != null) {
			sql += " LIMIT " + limit.toString();
		}
		
		logger.debug("executing query: " + sql);
		
		List<Update> updates = this.jdbcTemplate.query(
			sql,
			new RowMapper<Update>() {
				public Update mapRow(ResultSet results, int rowNum) throws SQLException {					
					Update update = new Update();
					
					update.setId(results.getLong(1));
					update.setPictionId(results.getInt(2));
					update.setFilename(results.getString(3));
					update.setMimeType(results.getString(4));
					update.setImgSize(results.getInt(5));
					update.setImgHeight(results.getInt(6));
					update.setImgWidth(results.getInt(7));
					update.setObjectCsid(results.getString(8));
					update.setMediaCsid(results.getString(9));
					update.setBlobCsid(results.getString(10));
					
					String actionString = results.getString(11);
					UpdateAction action = null;
					
					try {
						action = UpdateAction.valueOf(actionString);
					}
					catch(IllegalArgumentException e) {
						logger.warn("update " + update.getId() + " has unknown action " + actionString);
					}

					update.setAction(action);
					
					String relationshipString = results.getString(12);
					UpdateRelationship relationship = null;
					
					try {
						relationship = UpdateRelationship.valueOf(relationshipString);
					}
					catch(IllegalArgumentException e) {
						logger.warn("update " + update.getId() + " has unknown relationship " + relationshipString);
					}
					
					update.setRelationship(relationship);
					
					update.setDateTimeAddedToPiction(results.getTimestamp(13));
					update.setDateTimeUploaded(results.getTimestamp(14));
					update.setBinaryFile(extractBinary(results.getBinaryStream(15), update));

					logger.debug("found update\n" + update.toString());
					
					return update;
				}
			}
		);
		
		return updates;
	}
	
	public void deleteUpdate(Update update) {
		logger.debug("deleting update " + update.getId());

		int rowsAffected = this.jdbcTemplate.update("DELETE FROM " + getInterfaceTable() + " WHERE id = ?", Long.valueOf(update.getId()));
		
		if (rowsAffected != 1) {
			logger.warn("deletion of update " + update.getId() + " affected " + rowsAffected + " rows");
		}
	}

	private String getBinaryFilename(Update update) {
		return update.getFilename();
	}
	
	private Path getUpdateWorkDir(Update update) {
		return FileSystems.getDefault().getPath(getWorkPath(), BINARY_DIR, Long.toString(update.getId())).toAbsolutePath();
	}
	
	private File extractBinary(InputStream in, Update update) {
		Path dir = getUpdateWorkDir(update);
		
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			logger.fatal("failed to create work directory " + dir, e);
			
			throw(new UpdateMonitorException(e));
		}
	
		File file = new File(dir.toFile(), getBinaryFilename(update));
		
		logger.debug("extracting binary for update " + update.getId() + " to " + file.getPath());
		
		try {
			if (file.exists()) {
				logger.warn("binary file exists and will be overwritten: " + file.getPath());
			}
			else {
				file.createNewFile();
			}
			
			FileOutputStream out = new FileOutputStream(file);
			
			int bytesCopied = IOUtils.copy(in, out);
				
			in.close();
			out.close();
			
			if (update.getImgSize() != bytesCopied) {
				logger.warn("binary for update " + update.getId() + " has incorrect size: expected " + update.getImgSize() + ", found " + bytesCopied);
			}
		}
		catch(IOException e) {
			logger.error("error extracting binary for update " + update.getId(), e);
			return null;
		}
		
		return file;
	}
	
	public String getWorkPath() {
		return workPath;
	}

	public void setWorkPath(String workPath) {
		this.workPath = workPath;
		
		createWorkDirectories();
	}
	
	public String getInterfaceTable() {
		return interfaceTable;
	}

	public void setInterfaceTable(String interfaceTable) {
		this.interfaceTable = interfaceTable;
	}

	public DataSource getDataSource() {
		return dataSource;
	}

	public void setDataSource(DataSource dataSource) {
		this.dataSource = dataSource;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}
}
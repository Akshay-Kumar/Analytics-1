package edu.nyu.analytics.jobs;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

public class SongFrequency {

	static HashSet<String> ignoreSongs = new HashSet<String>();
	static Map<String, String> mapOfSongIDVsTrackID = new HashMap<String, String>();
	static Statement statement;
	static Connection connection;
	static PreparedStatement prep;

	static class SongFrequencyMapper extends Mapper<LongWritable, Text, Text, IntWritable> {

		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {

			String line = value.toString();
			String songId = null;
			String userId = null;

			if (line.split(",").length >= 2) {
				songId = line.split(",")[1];
				userId = line.split(",")[0];
			}

			if (line.split(",").length >= 2 && !ignoreSongs.contains(songId)) {
				String trackId = "";
				if (mapOfSongIDVsTrackID.containsKey(songId)) {
					trackId = mapOfSongIDVsTrackID.get(songId);
				}
				String frequency = line.split(",")[2];

				try {
					
					prep.setString(1, userId);
					prep.setString(2, trackId);
					prep.setInt(3, Integer.parseInt(frequency));
					prep.addBatch();
					
				} catch (NumberFormatException | SQLException e) {
					e.printStackTrace();
				}

				context.write(new Text(trackId), new IntWritable(Integer.parseInt(frequency)));
			}
		}
	}

	static class SongFrequencyReducer extends Reducer<Text, IntWritable, Text, IntWritable> {

		public void reduce(Text key, Iterable<IntWritable> values, Context context) throws IOException,
				InterruptedException {

			int totalFreq = 0;
			for (IntWritable value : values) {
				totalFreq = totalFreq + value.get();
			}
			context.write(key, new IntWritable(totalFreq));
		}
	}

	static class SongFrequencyMapper1 extends Mapper<LongWritable, Text, IntWritable, Text> {

		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {

			String line = value.toString();
			String trackId = null;

			if (line.split("\t").length >= 1 && !ignoreSongs.contains(trackId)) {
				trackId = line.split("\t")[0];
				int frequency = Integer.parseInt(line.split("\t")[1]);
				context.write(new IntWritable(frequency), new Text(trackId));
			}
		}
	}

	public static void main(String[] args) throws Exception {

		if (args.length != 3) {
			System.err.println("Usage: SongFrequency <input path> <temp path> <output path>");
			System.exit(-1);
		}

		BufferedReader reader = new BufferedReader(new FileReader(
				"/Users/hiral/Documents/RealTimeBigData/Data/ignore.txt"));
		String line = "";
		while ((line = reader.readLine()) != null) {
			ignoreSongs.add(line);
		}
		reader.close();

		BufferedReader reader1 = new BufferedReader(new FileReader(
				"/Users/hiral/Documents/RealTimeBigData/Data/allTrackEchonestId.txt"));
		String line1 = "";
		while ((line1 = reader1.readLine()) != null) {
			String[] arr = line1.split(",");
			mapOfSongIDVsTrackID.put(arr[1], arr[0]);
		}
		reader1.close();

		statement = dbConnect();
		statement.executeUpdate("drop table if exists metadata;");
		statement.executeUpdate("create table metadata (\"userid\" TEXT NOT NULL, \"trackid\" TEXT NOT NULL, \"frequency\" TEXT);");
		prep = connection.prepareStatement("insert into metadata values (?, ?, ?);");

		Configuration conf = new Configuration();
		Job job = new Job(conf, "first");
		job.setJarByClass(SongFrequency.class);

		FileInputFormat.addInputPath(job, new Path(args[0]));
		FileOutputFormat.setOutputPath(job, new Path(args[1]));

		job.setMapperClass(SongFrequencyMapper.class);
		job.setReducerClass(SongFrequencyReducer.class);

		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(IntWritable.class);
		job.waitForCompletion(true);

		connection.setAutoCommit(false);
		prep.executeBatch();
		connection.setAutoCommit(true);

		connection.close();
		
		System.out.println("First Job Completed.....Starting Second Job");
		System.out.println("Job completion was successful: " + job.isSuccessful());

		if (job.isSuccessful()) {
			System.out.println("Second job begins now..");

			Configuration conf2 = new Configuration();
			Job job2 = new Job(conf2, "second");
			job2.setJarByClass(SongFrequency.class);

			FileInputFormat.addInputPath(job2, new Path(args[1] + "/part-r-00000"));
			FileOutputFormat.setOutputPath(job2, new Path(args[2]));

			job2.setMapperClass(SongFrequencyMapper1.class);
			// job2.setReducerClass(SongFrequencyReducer.class);

			job2.setOutputKeyClass(IntWritable.class);
			job2.setOutputValueClass(Text.class);
			job2.waitForCompletion(true);

			System.out.println("Second job completed..");
			System.out.println("Job completion was successful: " + job2.isSuccessful());
		}
	}

	public static Statement dbConnect() throws ClassNotFoundException {

		Class.forName("org.sqlite.JDBC");

		try {
			// create a database connection
			connection = DriverManager
					.getConnection("jdbc:sqlite:/Users/hiral/Documents/RealTimeBigData/Data/song_frequency.db");
			Statement statement = connection.createStatement();
			statement.setQueryTimeout(30); // set timeout to 30 sec.
			return statement;
		} catch (SQLException e) {
			// if the error message is "out of memory",
			// it probably means no database file is found
			System.err.println(e.getMessage());
		}
		return null;
	}

}